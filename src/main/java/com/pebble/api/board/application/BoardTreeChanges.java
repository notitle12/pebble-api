package com.pebble.api.board.application;

import java.util.List;

public record BoardTreeChanges(List<Existing> base, List<Node> boards) {
    public record Existing(long id, String name, Long parentId, int displayOrder) { }
    public record Node(Long id, String name, List<Node> children) { }
}
