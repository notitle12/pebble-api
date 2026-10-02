package com.pebble.api.tag.application;

import com.pebble.api.tag.domain.TagStatus;
import java.util.Set;

public record TagChanges(Set<String> supplied, String name, String slug, Integer displayOrder, TagStatus status) {
    public boolean has(String field) { return supplied.contains(field); }
}
