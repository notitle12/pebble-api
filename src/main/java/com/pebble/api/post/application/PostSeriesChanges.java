package com.pebble.api.post.application;

public record PostSeriesChanges(String name, String description, Integer displayOrder) {
    public PostSeriesChanges(String name, String description) { this(name, description, null); }
}
