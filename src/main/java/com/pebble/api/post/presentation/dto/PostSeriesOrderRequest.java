package com.pebble.api.post.presentation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.post.application.PostSeriesOrderChanges;
import com.pebble.api.post.application.PostSeriesOrderChanges.Order;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PostSeriesOrderRequest {
    private PostSeriesOrderRequest() { }
    public static PostSeriesOrderChanges parse(JsonNode json) {
        fields(json, Set.of("seriesOrder", "postOrder"));
        if (json.isEmpty()) throw invalid();
        Order series = null, posts = null;
        Long id = null;
        if (json.has("seriesOrder")) {
            JsonNode part = json.get("seriesOrder");
            fields(part, Set.of("expectedIds", "ids"));
            if (part.size() != 2) throw invalid();
            series = order(part, 100);
        }
        if (json.has("postOrder")) {
            JsonNode part = json.get("postOrder");
            fields(part, Set.of("seriesId", "expectedIds", "ids"));
            if (part.size() != 3 || !part.get("seriesId").isTextual()) throw invalid();
            id = PostWriteRequest.id(part.get("seriesId").textValue(), "seriesId");
            posts = order(part, 10000);
        }
        return new PostSeriesOrderChanges(series, id, posts);
    }
    private static Order order(JsonNode json, int limit) {
        return new Order(ids(json.get("expectedIds"), limit), ids(json.get("ids"), limit));
    }
    private static List<Long> ids(JsonNode json, int limit) {
        if (json == null || !json.isArray() || json.size() > limit) throw invalid();
        var ids = new ArrayList<Long>();
        var unique = new HashSet<Long>();
        for (JsonNode value : json) {
            if (!value.isTextual()) throw invalid();
            long id = PostWriteRequest.id(value.textValue(), "ids");
            if (!unique.add(id)) throw invalid();
            ids.add(id);
        }
        return ids;
    }
    private static void fields(JsonNode json, Set<String> allowed) {
        if (json == null || !json.isObject()) throw invalid();
        json.fieldNames().forEachRemaining(field -> { if (!allowed.contains(field)) throw invalid(); });
    }
    private static ApplicationException invalid() {
        return new ApplicationException(GlobalErrorCode.VALIDATION_ERROR, "order", "저장할 시리즈와 글 순서를 확인해 주세요.");
    }
}
