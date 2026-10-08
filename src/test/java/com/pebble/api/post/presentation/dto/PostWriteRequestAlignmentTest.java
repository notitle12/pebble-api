package com.pebble.api.post.presentation.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.post.domain.BlockAlignment;
import org.junit.jupiter.api.Test;

class PostWriteRequestAlignmentTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void defaultsBlockAlignmentToLeftAndParsesSupportedValues() throws Exception {
        var changes = PostWriteRequest.parse(mapper.readTree("""
                {"blocks":[{"type":"TEXT","content":"default"},
                            {"type":"TEXT","content":"center","alignment":"CENTER"},
                            {"type":"TEXT","content":"right","alignment":"RIGHT"}]}
                """), false);

        assertThat(changes.blocks()).extracting("alignment")
                .containsExactly(BlockAlignment.LEFT, BlockAlignment.CENTER, BlockAlignment.RIGHT);
    }

    @Test
    void rejectsUnknownOrNullBlockAlignment() {
        assertThatThrownBy(() -> PostWriteRequest.parse(
                mapper.createObjectNode().putArray("blocks").addObject().put("type", "TEXT")
                        .put("content", "x").put("alignment", "JUSTIFY"), false))
                .isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> PostWriteRequest.parse(
                mapper.createObjectNode().putArray("blocks").addObject().put("type", "TEXT")
                        .put("content", "x").putNull("alignment"), false))
                .isInstanceOf(ApplicationException.class);
    }
}
