package com.pebble.api.post.application;

import java.util.List;

public record PostSeriesOrderChanges(Order seriesOrder, Long seriesId, Order postOrder) {
    public record Order(List<Long> expectedIds, List<Long> ids) { }
}
