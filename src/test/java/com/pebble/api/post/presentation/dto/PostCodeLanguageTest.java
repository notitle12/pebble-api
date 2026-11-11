package com.pebble.api.post.presentation.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.post.domain.CodeLanguage;
import org.junit.jupiter.api.Test;

class PostCodeLanguageTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsXmlCodeAndSerializesItsLanguageWithoutChangingContent() throws Exception {
        String xml = "<?xml version=\"1.0\"?>\n<root>\n  <item name=\"한글\"/>\n</root>";
        var input = mapper.createObjectNode();
        input.putArray("blocks").addObject().put("type", "CODE").put("content", xml).put("language", "XML");

        var block = PostWriteRequest.parse(input, false).blocks().getFirst();
        assertThat(block.language()).isEqualTo(CodeLanguage.XML);
        assertThat(block.content()).isEqualTo(xml);
        assertThat(mapper.writeValueAsString(block.language())).isEqualTo("\"XML\"");
    }

    @Test
    void acceptsMermaidSourceWithoutRewritingIt() {
        String source = "flowchart LR\n  A[요청] --> B[서버]";
        var input = mapper.createObjectNode();
        input.putArray("blocks").addObject().put("type", "CODE").put("content", source).put("language", "MERMAID");

        var block = PostWriteRequest.parse(input, false).blocks().getFirst();
        assertThat(block.language()).isEqualTo(CodeLanguage.MERMAID);
        assertThat(block.content()).isEqualTo(source);
    }

    @Test
    void stillRejectsUnsupportedCodeLanguages() {
        var input = mapper.createObjectNode();
        input.putArray("blocks").addObject().put("type", "CODE").put("content", "code").put("language", "UNKNOWN");
        assertThatThrownBy(() -> PostWriteRequest.parse(input, false)).isInstanceOf(ApplicationException.class);
    }
}
