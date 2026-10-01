package com.pebble.api.category.presentation.dto;

import com.pebble.api.category.domain.CategoryStatus;
import java.util.List;

public record CategoryResponse(
        String id, String parentId, String name, String slug, int displayOrder,
        CategoryStatus status, List<CategoryResponse> children) {
}
