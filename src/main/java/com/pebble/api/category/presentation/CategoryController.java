package com.pebble.api.category.presentation;

import com.pebble.api.category.application.CategoryQueryService;
import com.pebble.api.category.domain.Category;
import com.pebble.api.category.presentation.dto.CategoryResponse;
import com.pebble.api.global.presentation.response.ApiResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryQueryService categories;

    @GetMapping
    public ApiResponse<List<CategoryResponse>> tree() {
        return ApiResponse.of(categories.findPublicTree().stream().map(branch -> toResponse(branch.category(), null,
                branch.children().stream().map(child -> toResponse(child,
                        branch.category().getId().toString(), List.of())).toList())).toList());
    }

    private CategoryResponse toResponse(Category category, String parentId, List<CategoryResponse> children) {
        return new CategoryResponse(category.getId().toString(), parentId,
                category.getName(), category.getSlug(), category.getDisplayOrder(), category.getStatus(), children);
    }
}
