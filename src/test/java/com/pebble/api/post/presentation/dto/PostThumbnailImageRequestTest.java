package com.pebble.api.post.presentation.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.global.exception.ApplicationException;
import org.junit.jupiter.api.Test;

class PostThumbnailImageRequestTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void distinguishesAnOmittedThumbnailFromExplicitSelectionAndClear() throws Exception {
        var omitted = PostWriteRequest.parse(mapper.readTree("{\"title\":\"x\"}"), false);
        var selected = PostWriteRequest.parse(mapper.readTree("{\"thumbnailImageId\":\"721389012345678901\"}"), false);
        var cleared = PostWriteRequest.parse(mapper.readTree("{\"thumbnailImageId\":null}"), false);

        assertThat(omitted.has("thumbnailImageId")).isFalse();
        assertThat(selected.has("thumbnailImageId")).isTrue();
        assertThat(selected.thumbnailImageId()).isEqualTo(721389012345678901L);
        assertThat(cleared.has("thumbnailImageId")).isTrue();
        assertThat(cleared.thumbnailImageId()).isNull();
    }

    @Test
    void requiresThumbnailImageIdToBeAStringIdentifier() {
        assertThatThrownBy(() -> PostWriteRequest.parse(
                mapper.createObjectNode().put("thumbnailImageId", 123), false))
                .isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> PostWriteRequest.parse(
                mapper.createObjectNode().put("thumbnailImageId", "0"), false))
                .isInstanceOf(ApplicationException.class);
    }

    @Test
    void requiresAnExistingPostBeforeSelectingAnImage() throws Exception {
        assertThatThrownBy(() -> PostWriteRequest.parse(mapper.readTree("""
                {"title":"draft","visibilityStatus":"HIDDEN","blocks":[{"type":"TEXT","content":"x"}],
                 "thumbnailImageId":"721389012345678901"}
                """), true)).isInstanceOf(ApplicationException.class);
    }
}
