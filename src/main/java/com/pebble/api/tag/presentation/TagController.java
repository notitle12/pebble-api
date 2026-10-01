package com.pebble.api.tag.presentation;

import com.pebble.api.global.presentation.response.ApiResponse;
import com.pebble.api.tag.application.TagQueryService;
import com.pebble.api.tag.presentation.dto.TagResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tags")
@RequiredArgsConstructor
public class TagController {

    private final TagQueryService tags;

    @GetMapping
    public ApiResponse<List<TagResponse>> list() {
        return ApiResponse.of(tags.findPublicTags().stream().map(tag -> new TagResponse(
                tag.getId().toString(), tag.getName(), tag.getSlug(), tag.getDisplayOrder(), tag.getStatus())).toList());
    }
}
