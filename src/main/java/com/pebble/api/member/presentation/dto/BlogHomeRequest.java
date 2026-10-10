package com.pebble.api.member.presentation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.pebble.api.global.media.MediaRequest;
import com.pebble.api.member.application.BlogHomeService;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class BlogHomeRequest {
    private BlogHomeRequest() { }

    public static BlogHomeService.Settings parse(JsonNode json) {
        fields(json, Set.of("sections", "techStacks", "featuredProjectIds"));
        var sectionNodes = array(json.get("sections"), 4);
        if (sectionNodes.size() != 4) throw MediaRequest.invalid();
        List<BlogHomeService.Section> sections = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (var section : sectionNodes) {
            fields(section, Set.of("key", "visible"));
            var key = section.get("key");
            if (!key.isTextual() || !BlogHomeService.SECTION_KEYS.contains(key.textValue())
                    || !keys.add(key.textValue()) || !section.get("visible").isBoolean()) throw MediaRequest.invalid();
            sections.add(new BlogHomeService.Section(key.textValue(), section.get("visible").booleanValue()));
        }
        List<String> stacks = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (var item : array(json.get("techStacks"), 20)) {
            if (!item.isTextual()) throw MediaRequest.invalid();
            String name = Normalizer.normalize(item.textValue(), Normalizer.Form.NFKC).strip();
            if (name.isBlank() || name.codePointCount(0, name.length()) > 50
                    || name.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xd800 && c <= 0xdfff)
                    || !names.add(name.toLowerCase(Locale.ROOT))) throw MediaRequest.invalid();
            stacks.add(name);
        }
        List<String> ids = new ArrayList<>();
        for (var item : array(json.get("featuredProjectIds"), 6)) {
            if (!item.isTextual() || !item.textValue().matches("[1-9][0-9]{0,18}") || ids.contains(item.textValue())) throw MediaRequest.invalid();
            try { Long.parseLong(item.textValue()); } catch (NumberFormatException exception) { throw MediaRequest.invalid(); }
            ids.add(item.textValue());
        }
        return new BlogHomeService.Settings(List.copyOf(sections), List.copyOf(stacks), List.copyOf(ids));
    }

    private static JsonNode array(JsonNode node, int max) {
        if (node == null || !node.isArray() || node.size() > max) throw MediaRequest.invalid();
        return node;
    }

    private static void fields(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject() || node.size() != allowed.size()) throw MediaRequest.invalid();
        node.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) throw MediaRequest.invalid(); });
    }
}
