package com.pebble.api.comment.presentation.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.global.exception.ApplicationException;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GuestCommentRequestTest {
    private static final Set<String> CREATE_FIELDS = Set.of(
            "nickname", "password", "body", "privacyAccepted", "ageConfirmed");
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void requiresExactlyTheExpectedFields() {
        var missing = validCreate();
        missing.remove("ageConfirmed");
        assertInvalid(() -> GuestCommentRequest.validate(missing, CREATE_FIELDS));

        var extra = validCreate();
        extra.put("remoteAddress", "192.0.2.1");
        assertInvalid(() -> GuestCommentRequest.validate(extra, CREATE_FIELDS));

        assertInvalid(() -> GuestCommentRequest.validate(null, CREATE_FIELDS));
        assertInvalid(() -> GuestCommentRequest.validate(mapper.createArrayNode(), CREATE_FIELDS));
    }

    @Test
    void trimsNicknameAndCountsUnicodeCodePointsThroughThirtyCharacters() {
        var json = validCreate();
        json.put("nickname", "  " + "😀".repeat(30) + "  ");

        assertThat(GuestCommentRequest.nickname(json)).isEqualTo("😀".repeat(30));

        json.put("nickname", "😀".repeat(31));
        assertInvalid(() -> GuestCommentRequest.nickname(json));
    }

    @Test
    void rejectsEmptyOrControlCharacterNicknames() {
        for (String nickname : new String[]{"   ", "guest\nname", "guest\u0001name"}) {
            var json = validCreate();
            json.put("nickname", nickname);
            assertInvalid(() -> GuestCommentRequest.nickname(json));
        }
    }

    @Test
    void acceptsPasswordsFromEightThroughSixtyFourUnicodeCodePoints() {
        var json = validCreate();
        json.put("password", "🔒".repeat(8));
        assertThat(GuestCommentRequest.password(json)).isEqualTo("🔒".repeat(8));

        String fourByteCharacters = "😀".repeat(64);
        json.put("password", fourByteCharacters);
        assertThat(fourByteCharacters.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(256);
        assertThat(GuestCommentRequest.password(json)).isEqualTo(fourByteCharacters);
    }

    @Test
    void rejectsPasswordsOutsideUnicodeAndUtf8ByteLimits() {
        for (String password : new String[]{"short7", "😀".repeat(7), "😀".repeat(65), "\t".repeat(8)}) {
            var json = validCreate();
            json.put("password", password);
            assertInvalid(() -> GuestCommentRequest.password(json));
        }
    }

    @Test
    void rejectsUnpairedSurrogatesInNicknamePasswordAndBody() {
        var nickname = validCreate();
        nickname.put("nickname", "guest\uD800");
        assertInvalid(() -> GuestCommentRequest.nickname(nickname));

        var password = validCreate();
        password.put("password", "password\uD800");
        assertInvalid(() -> GuestCommentRequest.password(password));

        var body = validCreate();
        body.put("body", "text\uD800");
        assertInvalid(() -> GuestCommentRequest.body(body));
    }

    @Test
    void acceptsBodiesUpToTwoThousandCodePoints() {
        var json = validCreate();
        json.put("body", "가".repeat(2000));
        assertThat(GuestCommentRequest.body(json)).hasSize(2000);

        json.put("body", "가".repeat(2000) + "a");
        assertInvalid(() -> GuestCommentRequest.body(json));
    }

    @Test
    void requiresExplicitTrueForPrivacyAndAgeConfirmations() {
        GuestCommentRequest.confirmations(validCreate());

        for (String key : new String[]{"privacyAccepted", "ageConfirmed"}) {
            var falseValue = validCreate();
            falseValue.put(key, false);
            assertInvalid(() -> GuestCommentRequest.confirmations(falseValue));

            var missing = validCreate();
            missing.remove(key);
            assertInvalid(() -> GuestCommentRequest.confirmations(missing));

            var nonBoolean = validCreate();
            nonBoolean.put(key, "true");
            assertInvalid(() -> GuestCommentRequest.confirmations(nonBoolean));
        }
    }

    private com.fasterxml.jackson.databind.node.ObjectNode validCreate() {
        return mapper.createObjectNode()
                .put("nickname", "visitor")
                .put("password", "password8")
                .put("body", "hello")
                .put("privacyAccepted", true)
                .put("ageConfirmed", true);
    }

    private void assertInvalid(Runnable action) {
        assertThatThrownBy(() -> action.run()).isInstanceOf(ApplicationException.class);
    }
}
