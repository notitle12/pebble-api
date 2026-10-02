package com.pebble.api.category.application;

import com.pebble.api.category.domain.CategoryStatus;
import java.util.Set;

public record CategoryChanges(Set<String> supplied, String name, String slug, Long parentId,
        Integer displayOrder, CategoryStatus status) {
    public boolean has(String field) { return supplied.contains(field); }
}
