package com.pebble.api.board.presentation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.pebble.api.board.application.BoardTreeChanges;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class BoardTreeRequest {
    private BoardTreeRequest() { }

    public static BoardTreeChanges parse(JsonNode json) {
        fields(json, Set.of("base", "boards"));
        List<BoardTreeChanges.Existing> base = new ArrayList<>();
        array(json.get("base"));
        Set<Long> baseIds = new HashSet<>();
        for (JsonNode row : json.get("base")) {
            fields(row, Set.of("id", "name", "parentId", "displayOrder"));
            long id = id(row.get("id"));
            if (!baseIds.add(id)) fail();
            var input = row.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) input).remove("id");
            var values = BoardWriteRequest.parse(input, true);
            base.add(new BoardTreeChanges.Existing(id, values.name(), values.parentId(), values.displayOrder()));
        }
        return new BoardTreeChanges(List.copyOf(base), nodes(json.get("boards"), 1, new HashSet<>(), new int[]{0}));
    }

    private static List<BoardTreeChanges.Node> nodes(JsonNode json, int depth, Set<Long> ids, int[] count) {
        array(json);
        if (depth > 3 && !json.isEmpty()) fail();
        List<BoardTreeChanges.Node> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (JsonNode row : json) {
            if (++count[0] > 1000) fail();
            fields(row, Set.of("id", "name", "children"));
            Long id = row.get("id").isNull() ? null : id(row.get("id"));
            if (id != null && !ids.add(id)) fail();
            var nameJson = JsonNodeFactory.instance.objectNode().set("name", row.get("name"));
            String name = BoardWriteRequest.parse(nameJson, true).name();
            if (!names.add(name)) throw new ApplicationException(GlobalErrorCode.VALIDATION_ERROR,
                    "name", "같은 상위 게시판 안에서 이름이 중복될 수 없습니다.");
            result.add(new BoardTreeChanges.Node(id, name, nodes(row.get("children"), depth + 1, ids, count)));
        }
        return List.copyOf(result);
    }

    private static long id(JsonNode json) {
        if (json == null || !json.isTextual()) { fail(); }
        return BoardWriteRequest.id(json.textValue(), "id");
    }

    private static void fields(JsonNode json, Set<String> expected) {
        if (json == null || !json.isObject() || json.size() != expected.size()) fail();
        json.fieldNames().forEachRemaining(field -> { if (!expected.contains(field)) fail(); });
    }

    private static void array(JsonNode json) {
        if (json == null || !json.isArray() || json.size() > 1000) fail();
    }

    private static void fail() { throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST); }
}
