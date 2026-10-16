package com.pebble.api.comment.application;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GuestCommentNicknameTest {
    @Test void preservesUnusedNameAndSkipsTakenNumericSuffixes() {
        assertThat(CommentService.availableGuestNickname("조약돌", Set.of())).isEqualTo("조약돌");
        assertThat(CommentService.availableGuestNickname("조약돌", Set.of("조약돌", "조약돌1", "조약돌3"))).isEqualTo("조약돌2");
        assertThat(CommentService.availableGuestNickname("방문자1", Set.of("방문자1"))).isEqualTo("방문자11");
    }
    @Test void keepsThirtyCodePointLimitWithoutSplittingEmoji() {
        String base = "😀".repeat(30);
        String result = CommentService.availableGuestNickname(base, Set.of(base, "😀".repeat(29)+"1"));
        assertThat(result).isEqualTo("😀".repeat(29)+"2");
        assertThat(result.codePointCount(0,result.length())).isEqualTo(30);
    }
}
