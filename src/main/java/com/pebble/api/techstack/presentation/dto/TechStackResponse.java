package com.pebble.api.techstack.presentation.dto;

import com.pebble.api.techstack.domain.TechStackStatus;

public record TechStackResponse(String id, String name, String slug, int displayOrder, TechStackStatus status) {
}
