package com.pebble.api.update.presentation;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.pebble.api.global.exception.*;
import com.pebble.api.update.application.UpdateInput;
import com.pebble.api.update.domain.*;
import java.util.Set;
import org.springframework.util.MultiValueMap;

public final class UpdateRequest {
    private UpdateRequest() {}
    public static long id(String text) {
        try { if (!text.matches("[1-9][0-9]{0,18}")) throw invalid(); return Long.parseLong(text); }
        catch (NumberFormatException e) { throw invalid(); }
    }
    public static JsonNode parse(ObjectMapper mapper, String raw, Set<String> fields) {
        try {
            JsonNode body = mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw == null ? "" : raw);
            if (body == null || !body.isObject() || body.size() != fields.size()) throw invalid();
            body.fieldNames().forEachRemaining(f -> { if (!fields.contains(f)) throw invalid(); });
            return body;
        } catch (java.io.IOException e) { throw invalid(); }
    }
    public static UpdateInput input(JsonNode body) {
        JsonNode version = body.get("version");
        if (version == null || (!version.isNull() && !version.isTextual())) throw invalid();
        return new UpdateInput(text(body, "title"), text(body, "summary"), text(body, "body"),
            enumValue(UpdateType.class, text(body, "type")), version.isNull() ? null : version.textValue(),
            enumValue(UpdateStatus.class, text(body, "status")));
    }
    public static long revision(JsonNode body) {
        JsonNode n = body.get("expectedRevision");
        if (n == null || !n.isIntegralNumber() || !n.canConvertToLong() || n.longValue() < 0) throw invalid();
        return n.longValue();
    }
    private static String text(JsonNode body, String key) {
        JsonNode n = body.get(key); if (n == null || !n.isTextual()) throw invalid(); return n.textValue();
    }
    public static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        try { return value == null ? null : Enum.valueOf(type, value); }
        catch (IllegalArgumentException e) { throw invalid(); }
    }
    public static void query(MultiValueMap<String,String> query, Set<String> allowed) {
        query.forEach((key, value) -> { if (!allowed.contains(key) || value.size() != 1) throw invalid(); });
    }
    public static int page(MultiValueMap<String,String> q) { return number(q.getFirst("page"), 0, 0, 100000); }
    public static int size(MultiValueMap<String,String> q) { return number(q.getFirst("size"), 20, 1, 50); }
    private static int number(String value, int fallback, int min, int max) {
        if (value == null) return fallback;
        try { if (!value.matches("0|[1-9][0-9]*")) throw invalid(); int n = Integer.parseInt(value); if (n < min || n > max) throw invalid(); return n; }
        catch (NumberFormatException e) { throw invalid(); }
    }
    public static ApplicationException invalid() { return new ApplicationException(GlobalErrorCode.INVALID_REQUEST); }
}
