package com.pebble.api.post.presentation.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.post.domain.BlockType;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArchitectureSpecInputTest {
    final ObjectMapper mapper = new ObjectMapper();
    static final String VALID = """
        {"schemaVersion":1,"groups":[
          {"id":"oci","type":"ORACLE_CLOUD","label":"Oracle Cloud"},
          {"id":"docker","type":"DOCKER","label":"Docker","parentId":"oci"}],
         "nodes":[{"id":"app","type":"APP","label":"Spring Boot","groupId":"docker"},
          {"id":"db","type":"DATABASE","label":"PostgreSQL","groupId":"docker"}],
         "edges":[{"id":"sql","source":"app","target":"db","label":"SQL"}]}
        """;

    @Test void preservesNestedGroupsAndAllowsDirectedCyclesAndPlainTextLabels() throws Exception {
        ObjectNode spec = spec();
        spec.withArray("edges").addObject().put("id", "reply").put("source", "db").put("target", "app").putNull("label");
        ((ObjectNode) spec.withArray("nodes").get(0)).put("label", "<script>텍스트</script>");
        var parsed = PostWriteRequest.parse(request(spec.toString()), false).blocks().getFirst();
        assertThat(parsed.type()).isEqualTo(BlockType.ARCHITECTURE);
        assertThat(parsed.content()).isEqualTo(spec.toString());
        assertThat(parsed.language()).isNull();
    }

    @Test void rejectsMalformedVersionsUnknownFieldsAndDuplicateJsonKeys() {
        for (String value : List.of("null", "[]", "{", VALID + " {}", VALID.replace("1,", "2,"),
                VALID.replace("1,", "1.0,"), VALID.replace("1,", "\"1\","),
                VALID.replace("\"schemaVersion\":1,", "\"schemaVersion\":1,\"schemaVersion\":1,"),
                VALID.replace("\"groups\"", "\"unknown\""), VALID.replace("\"type\":\"APP\"", "\"html\":\"APP\""),
                VALID.replace("\"SQL\"", "false"))) reject(value);
    }

    @Test void validatesIdentifiersReferencesGroupDepthAndDuplicateConnections() {
        for (String value : List.of(VALID.replace("\"id\":\"app\"", "\"id\":\"oci\""),
                VALID.replace("\"id\":\"sql\"", "\"id\":\"db\""), VALID.replace("\"id\":\"sql\"", "\"id\":\"Bad ID\""),
                VALID.replace("\"parentId\":\"oci\"", "\"parentId\":\"docker\""),
                VALID.replace("\"parentId\":\"oci\"", "\"parentId\":\"absent\""),
                VALID.replace("\"DOCKER\"", "\"AWS\""), VALID.replace("\"ORACLE_CLOUD\"", "\"DOCKER\""),
                VALID.replace("\"groupId\":\"docker\"", "\"groupId\":\"absent\""),
                VALID.replace("\"target\":\"db\"", "\"target\":\"absent\""),
                VALID.replace("\"target\":\"db\"", "\"target\":\"app\""), VALID.replace("\"APP\"", "\"CUSTOM\""))) reject(value);
        ObjectNode spec;
        try { spec = spec(); } catch (Exception e) { throw new RuntimeException(e); }
        spec.withArray("edges").addObject().put("id", "duplicate").put("source", "app").put("target", "db");
        reject(spec.toString());
    }

    @Test void validatesArrayBoundariesAndOptionalNullReferences() throws Exception {
        ObjectNode spec = spec();
        spec.putArray("groups"); spec.putArray("edges");
        var nodes = spec.putArray("nodes"); reject(spec.toString());
        for (int n = 0; n < 30; n++) nodes.addObject().put("id", "n-" + n).put("type", "APP").put("label", "App").putNull("groupId");
        validate(spec.toString());
        nodes.addObject().put("id", "n-extra").put("type", "APP").put("label", "App"); reject(spec.toString()); nodes.remove(30);
        var groups = spec.withArray("groups");
        for (int n = 0; n < 10; n++) groups.addObject().put("id", "g-" + n).put("type", "AWS").put("label", "AWS").putNull("parentId");
        validate(spec.toString()); groups.addObject().put("id", "g-extra").put("type", "AWS").put("label", "AWS"); reject(spec.toString()); groups.remove(10);
        var edges = spec.withArray("edges");
        for (int n = 0; n < 60; n++) edges.addObject().put("id", "e-" + n).put("source", "n-" + n / 29).put("target", "n-" + ((n / 29 + 1 + n % 29) % 30));
        // 자기 연결 없이 출발점을 바꾸어 60개의 서로 다른 방향 연결을 만든다.
        validate(spec.toString()); edges.addObject().put("id", "e-extra").put("source", "n-29").put("target", "n-0"); reject(spec.toString());
    }

    @Test void validatesUnicodeLengthsLanguageAndExistingContentLimit() throws Exception {
        ObjectNode spec = spec(); var node = (ObjectNode) spec.withArray("nodes").get(0);
        node.put("label", "😀".repeat(100)); validate(spec.toString());
        node.put("label", "😀".repeat(101)); reject(spec.toString());
        for (String bad : List.of(" ", "\u0000", "\ud800")) { node.put("label", bad); reject(spec.toString()); }
        var req = request(VALID); ((ObjectNode) req.withArray("blocks").get(0)).put("language", "JAVA");
        assertThatThrownBy(() -> PostWriteRequest.parse(req, false)).isInstanceOf(ApplicationException.class);
        reject(" ".repeat(50001));
        spec = spec(); var edge = (ObjectNode) spec.withArray("edges").get(0);
        edge.put("label", "가".repeat(200)); validate(spec.toString()); edge.put("label", "가".repeat(201)); reject(spec.toString());
    }

    private ObjectNode spec() throws Exception { return (ObjectNode) mapper.readTree(VALID); }
    private void validate(String value) { PostWriteRequest.parse(request(value), false); }
    private void reject(String value) { assertThatThrownBy(() -> validate(value)).as(value).isInstanceOf(ApplicationException.class); }
    private ObjectNode request(String content) {
        ObjectNode request = mapper.createObjectNode();
        request.putArray("blocks").addObject().put("type", "ARCHITECTURE").put("content", content);
        return request;
    }
}
