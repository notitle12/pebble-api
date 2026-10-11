package com.pebble.api.post.presentation.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.post.domain.BlockType;
import java.util.List;
import org.junit.jupiter.api.Test;

class DatabaseSchemaInputTest {
    private final ObjectMapper json = new ObjectMapper();
    private static final String VALID = """
      {"schemaVersion":1,"display":"BOTH","tables":[
       {"id":"members","name":"회원","description":"","position":{"x":32,"y":32},"columns":[
        {"id":"id","name":"id","dataType":"BIGINT","primaryKey":true,"nullable":false,"unique":false,"description":"","reference":null}]},
       {"id":"posts","name":"posts","description":"","position":{"x":400,"y":32},"columns":[
        {"id":"member-id","name":"member_id","dataType":"BIGINT","primaryKey":false,"nullable":true,"unique":false,"description":"","reference":{"tableId":"members","columnId":"id"}}]}]}
      """;
    private ObjectNode request(String content) {
        ObjectNode req = json.createObjectNode(); req.putArray("blocks").addObject().put("type", "DB_SCHEMA").put("content", content); return req;
    }
    @Test void acceptsReferencesAndPreservesSharedSchemaDisplayAndLayout() {
        for (String display : List.of("BOTH", "ERD", "SPEC")) {
            String content = VALID.replace("BOTH", display);
            var block = PostWriteRequest.parse(request(content), false).blocks().getFirst();
            assertThat(block.type()).isEqualTo(BlockType.DB_SCHEMA); assertThat(block.content()).isEqualTo(content); assertThat(block.language()).isNull();
        }
    }
    @Test void rejectsDanglingReferencesDuplicateNamesUnknownFieldsAndInvalidKeys() {
        for (String content : List.of(VALID.replace("\"columnId\":\"id\"", "\"columnId\":\"missing\""),
                VALID.replace("\"tableId\":\"members\"", "\"tableId\":\"missing\""),
                VALID.replace("\"name\":\"posts\"", "\"name\":\" 회원 \""),
                VALID.replace("\"id\":\"posts\"", "\"id\":\"members\""),
                VALID.replace("\"primaryKey\":true", "\"primaryKey\":false"),
                VALID.replace("\"nullable\":false", "\"nullable\":true"),
                VALID.replace("\"unique\":false", "\"unique\":\"false\""),
                VALID.replace("\"x\":32", "\"x\":6001"),
                VALID.replace("\"x\":32", "\"x\":1.5"),
                VALID.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                VALID.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
                VALID.replace("\"description\":\"\"", "\"description\":\"\\u0000\""),
                VALID.replace("\"description\":\"\"", "\"unknown\":\"\""), VALID + " {}", "null", "[]")) {
            assertThatThrownBy(() -> PostWriteRequest.parse(request(content), false)).as(content).isInstanceOf(ApplicationException.class);
        }
    }
    @Test void rejectsSelfColumnReferenceAndCompositePkComponentUnlessExplicitlyUnique() throws Exception {
        ObjectNode schema = (ObjectNode) json.readTree(VALID);
        ObjectNode source = (ObjectNode) schema.get("tables").get(1).get("columns").get(0);
        ((ObjectNode) source.get("reference")).put("tableId", "posts").put("columnId", "member-id");
        source.put("unique", true);
        String self = schema.toString();
        assertThatThrownBy(() -> PostWriteRequest.parse(request(self), false)).isInstanceOf(ApplicationException.class);
        schema = (ObjectNode) json.readTree(VALID);
        var table = (ObjectNode) schema.get("tables").get(0);
        table.withArray("columns").add(table.get("columns").get(0).deepCopy());
        ((ObjectNode) table.get("columns").get(1)).put("id", "tenant").put("name", "tenant_id");
        final String composite = schema.toString();
        assertThatThrownBy(() -> PostWriteRequest.parse(request(composite), false)).isInstanceOf(ApplicationException.class);
        ((ObjectNode) table.get("columns").get(0)).put("unique", true);
        PostWriteRequest.parse(request(schema.toString()), false);
    }
    @Test void validatesTableAndColumnLimitsAndUnicodeLength() throws Exception {
        ObjectNode schema = (ObjectNode) json.readTree(VALID);
        var tables = schema.withArray("tables"); var template = tables.get(0).deepCopy(); tables.removeAll();
        for (int i=0;i<20;i++) tables.add(((ObjectNode) template.deepCopy()).put("id", "t-"+i).put("name", "t"+i));
        PostWriteRequest.parse(request(schema.toString()), false);
        tables.add(((ObjectNode) template.deepCopy()).put("id", "extra").put("name", "extra"));
        String self = schema.toString();
        assertThatThrownBy(() -> PostWriteRequest.parse(request(self), false)).isInstanceOf(ApplicationException.class);
        schema = (ObjectNode) json.readTree(VALID); var columns = ((ObjectNode) schema.get("tables").get(0)).withArray("columns"); var column = columns.get(0).deepCopy(); columns.removeAll();
        for(int i=0;i<51;i++) columns.add(((ObjectNode) column.deepCopy()).put("id", "c-"+i).put("name", "c"+i));
        String tooMany = schema.toString(); assertThatThrownBy(() -> PostWriteRequest.parse(request(tooMany), false)).isInstanceOf(ApplicationException.class);
        String longName = VALID.replace("会员", "😀".repeat(101)).replace("회원", "😀".repeat(101));
        assertThatThrownBy(() -> PostWriteRequest.parse(request(longName), false)).isInstanceOf(ApplicationException.class);
    }
    @Test void rejectsLanguageAndOversizedContent() {
        ObjectNode req=request(VALID); ((ObjectNode) req.get("blocks").get(0)).put("language", "SQL");
        assertThatThrownBy(() -> PostWriteRequest.parse(req, false)).isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> PostWriteRequest.parse(request(" ".repeat(50001)), false)).isInstanceOf(ApplicationException.class);
    }
}
