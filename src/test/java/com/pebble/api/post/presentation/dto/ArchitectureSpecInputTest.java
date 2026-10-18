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

    @Test void acceptsGenericToolboxAndPreservesNewFieldsThroughPostWrite() throws Exception {
        ObjectNode value = spec();
        ((ObjectNode)value.withArray("nodes").get(0)).put("icon", "PERSON");
        ObjectNode group = (ObjectNode)value.withArray("groups").get(0);
        group.put("icon", "CLOUD");
        ObjectNode edge = (ObjectNode)value.withArray("edges").get(0);
        for (String direction : List.of("NONE", "FORWARD", "BOTH")) {
            edge.put("direction", direction);
            var block = PostWriteRequest.parse(request(value.toString()), false).blocks().getFirst();
            assertThat(mapper.readTree(block.content()).at("/edges/0/direction").asText()).isEqualTo(direction);
            assertThat(mapper.readTree(block.content()).at("/groups/0/icon").asText()).isEqualTo("CLOUD");
        }
        edge.putNull("direction"); validate(value.toString());
        edge.put("direction", "INVALID"); reject(value.toString());
        edge.remove("direction");
        group.put("icon", "https://example.com/logo.svg"); reject(value.toString());
        group.put("icon", "PERSON"); validate(value.toString());
        group.put("icon", 1); reject(value.toString());
    }

    @Test void acceptsGenericGroupCategoriesAndOptionalDetails() throws Exception {
        ObjectNode value = spec();
        ((ObjectNode) value.withArray("groups").get(0)).put("type", "CLOUD").put("label", "");
        ((ObjectNode) value.withArray("groups").get(1)).put("type", "CONTAINER").put("label", "");
        validate(value.toString());
        ((ObjectNode) value.withArray("groups").get(0)).put("type", "CONTAINER");
        reject(value.toString());
        ((ObjectNode) value.withArray("groups").get(0)).put("type", "CUSTOM").put("typeLabel", "VPC");
        validate(value.toString());
        for (String color : List.of("BLUE", "GREEN", "PURPLE", "AMBER", "ROSE", "PEACH", "TEAL", "INDIGO", "PINK", "GRAY")) {
            ((ObjectNode) value.withArray("groups").get(0)).put("color", color);
            validate(value.toString());
        }
        ((ObjectNode) value.withArray("groups").get(0)).putNull("color");
        validate(value.toString());
        ((ObjectNode) value.withArray("groups").get(0)).put("color", "NEON_RED");
        reject(value.toString());
        ((ObjectNode) value.withArray("groups").get(0)).remove("color");
        ((ObjectNode) value.withArray("groups").get(0)).put("label", "x".repeat(101));
        reject(value.toString());
    }

    @Test void nestedGroupsAndColorsRoundTripButCyclesAndContainerParentsAreRejected() throws Exception {
        ObjectNode value = spec();
        ObjectNode outer = (ObjectNode) value.withArray("groups").get(0);
        ObjectNode inner = (ObjectNode) value.withArray("groups").get(1);
        outer.put("type", "CUSTOM").put("color", "TEAL");
        inner.put("type", "CUSTOM").put("color", "ROSE");
        value.withArray("groups").addObject().put("id", "leaf").put("type", "CONTAINER")
                .put("label", "실행 환경").put("parentId", "docker");
        var parsed = PostWriteRequest.parse(request(value.toString()), false).blocks().getFirst();
        assertThat(mapper.readTree(parsed.content()).at("/groups/0/color").asText()).isEqualTo("TEAL");
        assertThat(mapper.readTree(parsed.content()).at("/groups/2/parentId").asText()).isEqualTo("docker");
        outer.put("parentId", "docker"); reject(value.toString());
        outer.remove("parentId");
        outer.put("parentId", "leaf"); reject(value.toString());
        outer.remove("parentId");
        inner.put("parentId", "absent"); reject(value.toString());
        inner.put("parentId", "docker"); reject(value.toString());
        inner.put("parentId", "oci"); outer.put("type", "CONTAINER"); reject(value.toString());
        outer.put("type", "CUSTOM"); inner.put("color", 1); reject(value.toString());
    }

    @Test void acceptsExpandedTechnologyIconsButRejectsExternalImages() throws Exception {
        ObjectNode value = spec();
        ObjectNode node = (ObjectNode) value.withArray("nodes").get(0);
        for (String icon : List.of("REDUX", "JAVA", "SPRING_BOOT", "PYTHON", "NEXTJS", "MYSQL", "KUBERNETES", "GOOGLE_CLOUD", "PYTORCH", "OPENAI", "GITHUB_ACTIONS")) {
            node.put("icon", icon); validate(value.toString());
        }
        for (String icon : List.of("https://example.com/icon.svg", "UNKNOWN", "<svg/>")) {
            node.put("icon", icon); reject(value.toString());
        }
    }

    @Test void preservesNestedGroupsAndAllowsDirectedCyclesAndPlainTextLabels() throws Exception {
        ObjectNode spec = spec();
        spec.withArray("edges").addObject().put("id", "reply").put("source", "db").put("target", "app").putNull("label");
        ((ObjectNode) spec.withArray("nodes").get(0)).put("label", "<script>텍스트</script>");
        var parsed = PostWriteRequest.parse(request(spec.toString()), false).blocks().getFirst();
        assertThat(parsed.type()).isEqualTo(BlockType.ARCHITECTURE);
        assertThat(parsed.content()).isEqualTo(spec.toString());
        assertThat(parsed.language()).isNull();
    }

    @Test void preservesCustomTypeLabelsAndRejectsInvalidOrBuiltInOverrides() throws Exception {
        ObjectNode value = spec();
        ObjectNode group = (ObjectNode) value.withArray("groups").get(0);
        ObjectNode node = (ObjectNode) value.withArray("nodes").get(0);
        group.put("type", "CUSTOM").put("typeLabel", "개발 환경");
        node.put("type", "CUSTOM").put("typeLabel", "인증 서버");
        validate(value.toString());
        for (String label : List.of("", " ", "😀".repeat(51))) {
            node.put("typeLabel", label); reject(value.toString());
        }
        node.put("typeLabel", "😀".repeat(50)); validate(value.toString());
        node.put("type", "APP"); reject(value.toString());
        node.putNull("typeLabel"); validate(value.toString());
        group.put("type", "AWS"); reject(value.toString());
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
                VALID.replace("\"ORACLE_CLOUD\"", "\"DOCKER\""),
                VALID.replace("\"groupId\":\"docker\"", "\"groupId\":\"absent\""),
                VALID.replace("\"target\":\"db\"", "\"target\":\"absent\""),
                VALID.replace("\"target\":\"db\"", "\"target\":\"app\""), VALID.replace("\"APP\"", "\"UNKNOWN\""))) reject(value);
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

    @Test void customTechnologyAndPositionsRoundTripAndRejectUnsafeOrUnboundedFields() throws Exception {
        ObjectNode spec = spec();
        ((ObjectNode) spec.withArray("groups").get(0)).put("type", "CUSTOM").put("label", "우리 서버");
        var node = (ObjectNode) spec.withArray("nodes").get(0);
        node.put("type", "CUSTOM").put("icon", "DOCKER");
        var position = node.putObject("position").put("x", 0).put("y", 4000);
        validate(spec.toString());
        for (String bad : List.of("https://example.com/icon.svg", "<svg/>", "UNKNOWN")) {
            node.put("icon", bad); reject(spec.toString());
        }
        node.putNull("icon"); validate(spec.toString());
        for (int bad : List.of(-1, 4001)) { position.put("x", bad); reject(spec.toString()); }
        position.put("x", 1.5); reject(spec.toString());
        position.put("x", "1"); reject(spec.toString());
        position.put("x", 1).put("extra", 1); reject(spec.toString());
        position.remove("extra"); position.remove("y"); reject(spec.toString());
        node.putNull("position"); validate(spec.toString());
    }

    @Test void manualGroupBoundsAndGroupConnectionsPreserveAndValidateContract() throws Exception {
        ObjectNode spec = spec();
        var group = (ObjectNode) spec.withArray("groups").get(0);
        var bounds = group.putObject("bounds").put("x", 0).put("y", 0).put("width", 4200).put("height", 120);
        spec.withArray("edges").addObject().put("id", "boundary-flow").put("source", "oci").put("target", "docker");
        spec.withArray("edges").addObject().put("id", "card-flow").put("source", "docker").put("target", "db");
        validate(spec.toString());
        for (String key : List.of("x", "y", "width", "height")) {
            int original = bounds.get(key).intValue();
            bounds.put(key, -1); reject(spec.toString());
            bounds.put(key, 1.5); reject(spec.toString());
            bounds.put(key, "1"); reject(spec.toString());
            bounds.put(key, original);
        }
        bounds.put("width", 199); reject(spec.toString()); bounds.put("width", 4200);
        bounds.put("height", 119); reject(spec.toString()); bounds.put("height", 120);
        bounds.put("x", 1); reject(spec.toString()); bounds.put("x", 0);
        bounds.put("extra", 1); reject(spec.toString()); bounds.remove("extra");
        bounds.remove("height"); reject(spec.toString()); group.putNull("bounds"); validate(spec.toString());
        ((ObjectNode) spec.withArray("edges").get(1)).put("target", "oci"); reject(spec.toString());
    }

    @Test void arrowSidesAndWaypointPreserveAndRejectInvalidValues() throws Exception {
        ObjectNode spec = spec(); var edge = (ObjectNode) spec.withArray("edges").get(0);
        edge.put("sourceSide", "BOTTOM").put("targetSide", "LEFT");
        var waypoint = edge.putObject("waypoint").put("x", 0).put("y", 4200);
        validate(spec.toString());
        for (String side : List.of("sourceSide", "targetSide")) {
            for (String bad : List.of("top", "AUTO", "javascript:evil")) { edge.put(side,bad); reject(spec.toString()); }
            edge.putNull(side); validate(spec.toString());
        }
        for (int bad : List.of(-1,4201)) { waypoint.put("x",bad); reject(spec.toString()); }
        waypoint.put("x",1.5); reject(spec.toString()); waypoint.put("x", "1"); reject(spec.toString());
        waypoint.put("x",0).put("extra",1); reject(spec.toString()); waypoint.remove("extra");
        waypoint.remove("y"); reject(spec.toString()); edge.putNull("waypoint"); validate(spec.toString());
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
