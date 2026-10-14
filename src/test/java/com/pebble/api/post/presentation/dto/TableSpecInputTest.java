package com.pebble.api.post.presentation.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.post.domain.BlockType;
import java.util.List;
import org.junit.jupiter.api.Test;

class TableSpecInputTest {
    final ObjectMapper mapper = new ObjectMapper();
    static final String VALID = """
        {"schemaVersion":1,"tableName":"member","columns":[
          {"name":"id","dataType":"BIGINT","nullable":false,"primaryKey":true}
        ]}
        """;

    @Test void validTablePreservesContentAndAllowsOptionalDescriptionsAndCompositeKeys() throws Exception {
        ObjectNode table = (ObjectNode) mapper.readTree(VALID);
        table.putNull("description");
        table.withArray("columns").addObject().put("name", "tenant_id").put("dataType", "UUID")
                .put("nullable", false).put("primaryKey", true).put("foreignKey", "tenant.id").put("description", "<b>문자열</b>");
        var block = parse(table.toString()).blocks().getFirst();
        assertThat(block.type()).isEqualTo(BlockType.TABLE);
        assertThat(block.content()).isEqualTo(table.toString());
        assertThat(block.language()).isNull();
    }

    @Test void invalidJsonVersionsUnknownKeysAndDuplicateJsonKeysAreRejected() {
        for (String value : List.of("", "null", "[]", "{", VALID + " {}", VALID.replace("1,", "2,"),
                VALID.replace("1,", "1.0,"), VALID.replace("1,", "\"1\","),
                VALID.replace("\"schemaVersion\":1,", "\"schemaVersion\":1,\"schemaVersion\":1,"),
                VALID.replace("\"tableName\"", "\"unexpected\""), VALID.replace("\"dataType\"", "\"unexpected\""))) {
            assertThatThrownBy(() -> parse(value)).as(value).isInstanceOf(ApplicationException.class);
        }
    }

    @Test void columnCountsNamesRequiredBooleansAndNullablePrimaryKeysAreValidated() throws Exception {
        for (String replacement : List.of("", "ID", " id ")) {
            ObjectNode table = (ObjectNode) mapper.readTree(VALID);
            table.withArray("columns").addObject().put("name", replacement).put("dataType", "BIGINT").put("nullable", false).put("primaryKey", false);
            assertThatThrownBy(() -> parse(table.toString())).isInstanceOf(ApplicationException.class);
        }
        for (String value : List.of(VALID.replace("false", "true"), VALID.replace("false", "\"false\""),
                VALID.replace("\"nullable\":false,", ""), VALID.replace("\"primaryKey\":true", "\"primaryKey\":null"),
                VALID.replace("\"dataType\":\"BIGINT\",", ""), VALID.replace("BIGINT", " "))) {
            assertThatThrownBy(() -> parse(value)).isInstanceOf(ApplicationException.class);
        }
        ObjectNode table = (ObjectNode) mapper.readTree(VALID);
        var columns = table.putArray("columns");
        assertThatThrownBy(() -> parse(table.toString())).isInstanceOf(ApplicationException.class);
        for (int n = 0; n < 50; n++) columns.addObject().put("name", "col_" + n).put("dataType", "TEXT").put("nullable", true).put("primaryKey", false);
        parse(table.toString());
        columns.add(columns.get(0).deepCopy());
        assertThatThrownBy(() -> parse(table.toString())).isInstanceOf(ApplicationException.class);
    }

    @Test void stringLimitsAndUnicodeInsideEscapedJsonAreValidated() throws Exception {
        ObjectNode table = (ObjectNode) mapper.readTree(VALID);
        table.put("tableName", "가".repeat(100)); parse(table.toString());
        table.put("tableName", "가".repeat(101));
        assertThatThrownBy(() -> parse(table.toString())).isInstanceOf(ApplicationException.class);
        table.put("tableName", "member").put("description", "😀".repeat(500)); parse(table.toString());
        table.put("description", "😀".repeat(501));
        assertThatThrownBy(() -> parse(table.toString())).isInstanceOf(ApplicationException.class);
        for (String invalid : List.of("\u0000", "\ud800")) {
            table.put("description", invalid);
            assertThatThrownBy(() -> parse(table.toString())).isInstanceOf(ApplicationException.class);
        }
        assertThatThrownBy(() -> parse(VALID.replace("member", " "))).isInstanceOf(ApplicationException.class);
    }

    @Test void tableCannotSpecifyCodeLanguageAndContentStillUsesTheExistingLimit() throws Exception {
        var request = request(VALID);
        ((ObjectNode) request.withArray("blocks").get(0)).put("language", "JAVA");
        assertThatThrownBy(() -> PostWriteRequest.parse(request, false)).isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> parse(" ".repeat(50001))).isInstanceOf(ApplicationException.class);
    }

    private com.pebble.api.post.application.PostChanges parse(String content) {
        return PostWriteRequest.parse(request(content), false);
    }
    private ObjectNode request(String content) {
        ObjectNode request = mapper.createObjectNode();
        request.putArray("blocks").addObject().put("type", "TABLE").put("content", content);
        return request;
    }
}
