package com.pebble.api.tag.presentation.dto;

import com.pebble.api.tag.domain.TagStatus;

public record TagResponse(String id, String name, String slug, int displayOrder, TagStatus status) {
}
