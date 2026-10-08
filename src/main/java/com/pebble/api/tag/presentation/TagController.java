package com.pebble.api.tag.presentation;

import com.pebble.api.global.presentation.response.ApiResponse;
import com.pebble.api.tag.application.TagQueryService;
import com.pebble.api.tag.application.MemberTagService;
import com.pebble.api.tag.presentation.dto.MemberTagRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final MemberTagService memberTags;
    private final ObjectMapper mapper;

    @PostMapping(consumes = "application/json")
    public ApiResponse<TagResponse> create(@AuthenticationPrincipal Jwt jwt, @RequestBody(required = false) String request,
                                           @RequestParam MultiValueMap<String, String> query) {
        if (!query.isEmpty()) throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
        var tag = memberTags.create(Long.parseLong(jwt.getSubject().substring("member:".length())),
                MemberTagRequest.parse(mapper, request));
        return ApiResponse.of(new TagResponse(tag.getId().toString(), tag.getName(), tag.getSlug(),
                tag.getDisplayOrder(), tag.getStatus()));
    }

    @GetMapping
    public ApiResponse<List<TagResponse>> list() {
        return ApiResponse.of(tags.findPublicTags().stream().map(tag -> new TagResponse(
                tag.getId().toString(), tag.getName(), tag.getSlug(), tag.getDisplayOrder(), tag.getStatus())).toList());
    }
}
