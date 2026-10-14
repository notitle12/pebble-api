package com.pebble.api.comment.presentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.pebble.api.comment.application.CommentService;
import com.pebble.api.comment.domain.CommentTarget;
import com.pebble.api.comment.presentation.dto.CommentResponse;
import com.pebble.api.comment.presentation.dto.CommentResponse.CommentPage;
import com.pebble.api.comment.presentation.dto.CommentWriteRequest;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.global.presentation.response.ApiResponse;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/{resource:posts|projects}/{contentId:[0-9]+}/comments")
public class CommentController {
    private final com.pebble.api.member.application.MemberProfileImages profileImages;
    private final CommentService comments;

    @PostMapping(consumes = "application/json")
    public ResponseEntity<ApiResponse<CommentResponse>> create(@PathVariable String resource, @PathVariable String contentId,
            @AuthenticationPrincipal Jwt jwt, @RequestParam MultiValueMap<String, String> query, @RequestBody JsonNode body) {
        checkQuery(query, Set.of());
        var response = response(comments.create(target(resource), CommentWriteRequest.id(contentId),
                requester(jwt), CommentWriteRequest.parse(body, true)));
        return ResponseEntity.created(UriComponentsBuilder.fromPath("/api/v1/{resource}/{id}/comments/{commentId}")
                .buildAndExpand(resource, contentId, response.id()).encode().toUri()).body(ApiResponse.of(response));
    }

    @GetMapping
    public ApiResponse<CommentPage> list(@PathVariable String resource, @PathVariable String contentId,
            @AuthenticationPrincipal Jwt jwt, @RequestParam MultiValueMap<String, String> query) {
        checkQuery(query, Set.of("page", "size", "sort"));
        return ApiResponse.of(page(comments.list(target(resource), CommentWriteRequest.id(contentId),
                requester(jwt), pageable(query))));
    }

    @GetMapping("/{commentId:[0-9]+}")
    public ApiResponse<CommentResponse> detail(@PathVariable String resource, @PathVariable String contentId,
            @PathVariable String commentId, @AuthenticationPrincipal Jwt jwt, @RequestParam MultiValueMap<String, String> query) {
        checkQuery(query, Set.of());
        return ApiResponse.of(response(comments.detail(target(resource), CommentWriteRequest.id(contentId),
                CommentWriteRequest.id(commentId), requester(jwt))));
    }

    @PatchMapping(value = "/{commentId:[0-9]+}", consumes = "application/json")
    public ApiResponse<CommentResponse> update(@PathVariable String resource, @PathVariable String contentId,
            @PathVariable String commentId, @AuthenticationPrincipal Jwt jwt,
            @RequestParam MultiValueMap<String, String> query, @RequestBody JsonNode body) {
        checkQuery(query, Set.of());
        return ApiResponse.of(response(comments.update(target(resource), CommentWriteRequest.id(contentId),
                CommentWriteRequest.id(commentId), requester(jwt), CommentWriteRequest.parse(body, false))));
    }

    @DeleteMapping("/{commentId:[0-9]+}")
    public ResponseEntity<Void> delete(@PathVariable String resource, @PathVariable String contentId,
            @PathVariable String commentId, @AuthenticationPrincipal Jwt jwt,
            @RequestParam MultiValueMap<String, String> query, @RequestBody(required = false) String body) {
        checkQuery(query, Set.of());
        if (body != null) throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
        comments.delete(target(resource), CommentWriteRequest.id(contentId), CommentWriteRequest.id(commentId), requester(jwt));
        return ResponseEntity.noContent().build();
    }

    private CommentTarget target(String resource) { return resource.equals("posts") ? CommentTarget.POST : CommentTarget.PROJECT; }
    private Long requester(Jwt jwt) {
        return jwt != null && "USER".equals(jwt.getClaimAsString("role"))
                ? Long.parseLong(jwt.getSubject().substring("member:".length())) : null;
    }
    private PageRequest pageable(MultiValueMap<String, String> query) {
        int page = integer(query.getFirst("page"), 0);
        int size = integer(query.getFirst("size"), 20);
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) throw invalid("page/size");
        String value = query.getFirst("sort");
        String[] parts = value == null ? new String[]{"createdAt", "asc"} : value.split(",", -1);
        if (parts.length != 2 || !Set.of("createdAt", "updatedAt").contains(parts[0])
                || !Set.of("asc", "desc").contains(parts[1])) throw invalid("sort");
        return PageRequest.of(page, size, Sort.by(Sort.Direction.fromString(parts[1]), parts[0], "id"));
    }
    private int integer(String value, int fallback) {
        if (value == null) return fallback;
        try {
            if (!value.matches("[0-9]+")) throw new NumberFormatException();
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) { throw invalid("page/size"); }
    }
    private void checkQuery(MultiValueMap<String, String> query, Set<String> allowed) {
        for (var entry : query.entrySet()) {
            if (!allowed.contains(entry.getKey()) || entry.getValue().size() != 1) throw invalid(entry.getKey());
        }
    }
    private ApplicationException invalid(String field) {
        return new ApplicationException(GlobalErrorCode.VALIDATION_ERROR, field, "지원되는 요청 값을 입력해 주세요.");
    }
    private CommentResponse response(com.pebble.api.comment.domain.Comment view) { return CommentResponse.from(view, profileImages::url); }
    private CommentPage page(org.springframework.data.domain.Page<com.pebble.api.comment.domain.Comment> view) { return CommentPage.from(view, profileImages::url); }

}
