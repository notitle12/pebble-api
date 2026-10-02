package com.pebble.api.like.domain;

public record LikeSummary(long count, boolean likedByMe) {
    public static final LikeSummary EMPTY = new LikeSummary(0, false);
}
