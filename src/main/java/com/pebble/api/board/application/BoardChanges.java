package com.pebble.api.board.application;

import java.util.Set;

public record BoardChanges(Set<String> supplied, String name, Long parentId, Integer displayOrder) {
    public boolean has(String field) {
        return supplied.contains(field);
    }
}
