package com.pebble.api.post.presentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.global.presentation.response.ApiResponse;
import com.pebble.api.post.application.PostService;
import com.pebble.api.post.domain.PostVisibility;
import com.pebble.api.post.presentation.dto.PostResponse;
import com.pebble.api.post.presentation.dto.PostResponse.PostPage;
import com.pebble.api.post.presentation.dto.PostWriteRequest;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PostController {
    private final PostService posts;

    @PostMapping(value = "/api/v1/posts", consumes = "application/json")
    public ResponseEntity<ApiResponse<PostResponse>> create(@AuthenticationPrincipal Jwt jwt, @RequestBody JsonNode request) {
        PostResponse response = PostResponse.from(posts.create(memberId(jwt), PostWriteRequest.parse(request, true)));
        return ResponseEntity.created(org.springframework.web.util.UriComponentsBuilder.fromPath("/api/v1/blogs/{handle}/posts/{key}")
                .buildAndExpand(response.author().handle(), response.urlKey()).encode().toUri()).body(ApiResponse.of(response));
    }

    @GetMapping("/api/v1/posts/{postId:[0-9]+}")
    public ApiResponse<PostResponse> detail(@PathVariable String postId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of(PostResponse.from(posts.detail(PostWriteRequest.id(postId, "postId"), jwt == null ? null : memberId(jwt))));
    }

    @GetMapping("/api/v1/blogs/{handle}/posts/{postKey}")
    public ApiResponse<PostResponse> publicAddress(@PathVariable String handle, @PathVariable String postKey, @AuthenticationPrincipal Jwt jwt) {
        Long requester = jwt == null ? null : memberId(jwt);
        return ApiResponse.of(PostResponse.from(postKey.matches("[0-9]+")
                ? posts.detailByNumber(handle, PostWriteRequest.id(postKey, "postNumber"), requester)
                : posts.detailBySlug(handle, PostWriteRequest.normalizeSlug(postKey), requester)));
    }

    @PatchMapping(value = "/api/v1/posts/{postId:[0-9]+}", consumes = "application/json")
    public ApiResponse<PostResponse> update(@PathVariable String postId, @AuthenticationPrincipal Jwt jwt,
                                            @RequestBody JsonNode request) {
        return ApiResponse.of(PostResponse.from(posts.update(PostWriteRequest.id(postId, "postId"), memberId(jwt),
                PostWriteRequest.parse(request, false))));
    }

    @DeleteMapping("/api/v1/posts/{postId:[0-9]+}")
    public ResponseEntity<Void> delete(@PathVariable String postId, @AuthenticationPrincipal Jwt jwt) {
        posts.delete(PostWriteRequest.id(postId, "postId"), memberId(jwt));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/posts")
    public ApiResponse<PostPage> list(@RequestParam MultiValueMap<String, String> query) {
        checkQuery(query, Set.of("page", "size", "sort", "categoryId", "tagId", "authorId"));
        return ApiResponse.of(PostPage.from(posts.listPublic(optionalId(query, "categoryId"), optionalId(query, "tagId"),
                optionalId(query, "authorId"), pageable(query, "publishedAt"))));
    }

    @GetMapping("/api/v1/posts/search")
    public ApiResponse<PostPage> search(@RequestParam MultiValueMap<String, String> query) {
        checkQuery(query, Set.of("q", "page", "size", "sort", "categoryId", "tagId", "authorId"));
        return ApiResponse.of(PostPage.from(posts.search(PostWriteRequest.searchTerm(query.getFirst("q")),
                optionalId(query, "categoryId"), optionalId(query, "tagId"), optionalId(query, "authorId"),
                pageable(query, "publishedAt"))));
    }

    @GetMapping("/api/v1/members/me/posts")
    public ApiResponse<PostPage> mine(@AuthenticationPrincipal Jwt jwt, @RequestParam MultiValueMap<String, String> query) {
        checkQuery(query, Set.of("page", "size", "sort", "visibilityStatus"));
        PostVisibility visibility = null;
        if (query.containsKey("visibilityStatus")) {
            try {
                visibility = PostVisibility.valueOf(query.getFirst("visibilityStatus"));
                if (visibility == PostVisibility.DELETED) throw new IllegalArgumentException();
            } catch (IllegalArgumentException exception) {
                throw invalid("visibilityStatus");
            }
        }
        return ApiResponse.of(PostPage.from(posts.listMine(memberId(jwt), visibility, pageable(query, "displayOrder"))));
    }

    @GetMapping("/api/v1/members/{memberId:[0-9]+}/posts")
    public ApiResponse<PostPage> blog(@PathVariable String memberId, @RequestParam MultiValueMap<String, String> query) {
        checkQuery(query, Set.of("page", "size", "sort", "categoryId", "tagId"));
        return ApiResponse.of(PostPage.from(posts.listPublic(optionalId(query, "categoryId"), optionalId(query, "tagId"),
                PostWriteRequest.id(memberId, "memberId"), pageable(query, "displayOrder"))));
    }

    @GetMapping("/api/v1/blogs/{handle}/posts")
    public ApiResponse<PostPage> publicBlog(@PathVariable String handle, @RequestParam MultiValueMap<String, String> query) {
        checkQuery(query, Set.of("page", "size", "sort", "categoryId", "tagId"));
        return ApiResponse.of(PostPage.from(posts.listBlog(handle, optionalId(query, "categoryId"), optionalId(query, "tagId"),
                pageable(query, "displayOrder"))));
    }

    @GetMapping("/api/v1/members/{memberId:[0-9]+}/boards/{boardId:[0-9]+}/posts")
    public ApiResponse<PostPage> board(@PathVariable String memberId, @PathVariable String boardId,
                                      @RequestParam MultiValueMap<String, String> query) {
        checkQuery(query, Set.of("page", "size", "sort"));
        return ApiResponse.of(PostPage.from(posts.listBoard(PostWriteRequest.id(memberId, "memberId"),
                PostWriteRequest.id(boardId, "boardId"), pageable(query, "displayOrder"))));
    }

    private long memberId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject().substring("member:".length()));
    }

    private Long optionalId(MultiValueMap<String, String> query, String field) {
        return query.containsKey(field) ? PostWriteRequest.id(query.getFirst(field), field) : null;
    }

    private PageRequest pageable(MultiValueMap<String, String> query, String defaultField) {
        int page = integer(query.getFirst("page"), 0, "page");
        int size = integer(query.getFirst("size"), 20, "size");
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) throw invalid("page/size");
        String value = query.getFirst("sort");
        String[] parts = value == null ? new String[]{defaultField, defaultField.equals("displayOrder") ? "asc" : "desc"} : value.split(",", -1);
        Set<String> allowed = defaultField.equals("displayOrder") ? Set.of("publishedAt", "createdAt", "displayOrder") : Set.of("publishedAt", "createdAt");
        if (parts.length != 2 || !allowed.contains(parts[0])
                || !Set.of("asc", "desc").contains(parts[1])) throw invalid("sort");
        Sort.Direction direction = Sort.Direction.fromString(parts[1]);
        return PageRequest.of(page, size, Sort.by(direction, parts[0], "id"));
    }

    private int integer(String value, int fallback, String field) {
        if (value == null) return fallback;
        try {
            if (!value.matches("[0-9]+")) throw new NumberFormatException();
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw invalid(field);
        }
    }

    private void checkQuery(MultiValueMap<String, String> query, Set<String> allowed) {
        for (var entry : query.entrySet()) {
            if (!allowed.contains(entry.getKey()) || entry.getValue().size() != 1) throw invalid(entry.getKey());
        }
    }

    private ApplicationException invalid(String field) {
        return new ApplicationException(GlobalErrorCode.VALIDATION_ERROR, field, "지원되는 요청 값을 입력해 주세요.");
    }
}
