package com.pebble.api.comment.presentation.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.global.exception.ApplicationException;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GuestCommentRequestTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsOnlyCreateFieldsAndRequiresPasswordAndBody() {
        GuestCommentRequest.validateCreate(validCreate());
        assertInvalid(() -> GuestCommentRequest.validateCreate(mapper.createObjectNode().put("body", "hello")));
        assertInvalid(() -> GuestCommentRequest.validateCreate(mapper.createObjectNode().put("password", "pass")));
        assertInvalid(() -> GuestCommentRequest.validateCreate(mapper.createArrayNode()));
        assertInvalid(() -> GuestCommentRequest.validateCreate(validCreate().put("remoteAddress", "192.0.2.1")));
        GuestCommentRequest.validateCreate(validCreate().put("visibility", "SECRET"));
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
        for (String nickname : new String[]{"guest\nname", "guest\u0001name"}) {
            var json = validCreate();
            json.put("nickname", nickname);
            assertInvalid(() -> GuestCommentRequest.nickname(json));
        }
    }

    @Test
    void usesPebbleAsTheDefaultForMissingOrBlankNickname() {
        assertThat(GuestCommentRequest.nickname(validCreate())).isEqualTo("조약돌");
        assertThat(GuestCommentRequest.nickname(validCreate().put("nickname", "  "))).isEqualTo("조약돌");
    }

    @Test
    void newPasswordsAreFourThroughTenUnicodeCharactersButLegacyVerificationAllowsSixtyFour() {
        var json = validCreate();
        json.put("password", "🔒".repeat(4));
        assertThat(GuestCommentRequest.newPassword(json)).isEqualTo("🔒".repeat(4));
        json.put("password", "🔒".repeat(10));
        assertThat(GuestCommentRequest.newPassword(json)).isEqualTo("🔒".repeat(10));
        json.put("password", "🔒".repeat(11));
        assertInvalid(() -> GuestCommentRequest.newPassword(json));
        assertThat(GuestCommentRequest.password(json)).isEqualTo("🔒".repeat(11));

        String fourByteCharacters = "😀".repeat(64);
        json.put("password", fourByteCharacters);
        assertThat(fourByteCharacters.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(256);
        assertThat(GuestCommentRequest.password(json)).isEqualTo(fourByteCharacters);
        assertInvalid(() -> GuestCommentRequest.newPassword(json));
    }

    @Test
    void rejectsPasswordsOutsideUnicodeAndUtf8ByteLimits() {
        for (String password : new String[]{"abc", "😀".repeat(3), "😀".repeat(65), "\t".repeat(8)}) {
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
    void ignoresOptionalLegacyConsentFlagsButRequiresBooleanTypes() {
        GuestCommentRequest.validateCreate(validCreate());
        GuestCommentRequest.validateCreate(validCreate().put("privacyAccepted", false).put("ageConfirmed", true));
        GuestCommentRequest.validateCreate(validCreate().put("ageConfirmed", false));
        assertInvalid(() -> GuestCommentRequest.validateCreate(validCreate().put("ageConfirmed", "true")));
        assertInvalid(() -> GuestCommentRequest.validateCreate(validCreate().putNull("privacyAccepted")));
    }

    private com.fasterxml.jackson.databind.node.ObjectNode validCreate() {
        return mapper.createObjectNode()
                .put("password", "pass")
                .put("body", "hello");
    }

    private void assertInvalid(Runnable action) {
        assertThatThrownBy(() -> action.run()).isInstanceOf(ApplicationException.class);
    }
}
