package com.pebble.api.techstack.application;

import com.pebble.api.techstack.domain.TechStackStatus;
import java.util.Set;

public record TechStackChanges(Set<String> supplied, String name, String slug, Integer displayOrder, TechStackStatus status) {
    public boolean has(String field) { return supplied.contains(field); }
}
