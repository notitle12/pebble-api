package com.pebble.api.post.presentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.global.presentation.response.ApiResponse;
import com.pebble.api.like.application.PostLikeService;
import com.pebble.api.member.application.MemberProfileImages;
import com.pebble.api.member.application.MemberQueryService;
import com.pebble.api.post.application.PostSeriesService;
import com.pebble.api.post.application.PostSeriesService.SeriesView;
import com.pebble.api.post.application.PostService;
import com.pebble.api.post.presentation.dto.PostResponse.PostPage;
import com.pebble.api.post.presentation.dto.PostSeriesWriteRequest;
import com.pebble.api.post.presentation.dto.PostSeriesOrderRequest;
import com.pebble.api.post.presentation.dto.PostWriteRequest;
import java.net.URI;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PostSeriesController {
    private final PostSeriesService series;
    private final PostService posts;
    private final MemberQueryService members;
    private final MemberProfileImages profileImages;
    private final PostLikeService likes;

    @GetMapping("/api/v1/members/me/series")
    public ApiResponse<List<SeriesView>> mine(@AuthenticationPrincipal Jwt jwt, @RequestParam MultiValueMap<String,String> query) {
        checkQuery(query, Set.of());
        return ApiResponse.of(series.mine(memberId(jwt)));
    }
    @PostMapping(value = "/api/v1/series", consumes = "application/json")
    public ResponseEntity<ApiResponse<SeriesView>> create(@AuthenticationPrincipal Jwt jwt, @RequestBody JsonNode request) {
        var created = series.create(memberId(jwt), PostSeriesWriteRequest.parse(request, true));
        return ResponseEntity.created(URI.create("/api/v1/series/" + created.id())).body(ApiResponse.of(created));
    }
    @GetMapping("/api/v1/series/{id:[0-9]+}")
    public ApiResponse<SeriesView> own(@PathVariable String id, @AuthenticationPrincipal Jwt jwt, @RequestParam MultiValueMap<String,String> query) {
        checkQuery(query, Set.of());
        return ApiResponse.of(series.own(id(id), memberId(jwt)));
    }
    @PatchMapping(value = "/api/v1/series/{id:[0-9]+}", consumes = "application/json")
    public ApiResponse<SeriesView> update(@PathVariable String id, @AuthenticationPrincipal Jwt jwt, @RequestBody JsonNode request) {
        return ApiResponse.of(series.update(id(id), memberId(jwt), PostSeriesWriteRequest.parse(request, false)));
    }
    @DeleteMapping("/api/v1/series/{id:[0-9]+}")
    public ResponseEntity<Void> delete(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        series.delete(id(id), memberId(jwt));
        return ResponseEntity.noContent().build();
    }
    @GetMapping("/api/v1/series/{id:[0-9]+}/posts")
    public ApiResponse<PostPage> ownPosts(@PathVariable String id, @AuthenticationPrincipal Jwt jwt, @RequestParam MultiValueMap<String,String> query) {
        long memberId = memberId(jwt);
        return ApiResponse.of(PostPage.from(likes.decorate(posts.listSeries(id(id), memberId, true, pageable(query)), memberId), profileImages::url));
    }
    @PutMapping(value = "/api/v1/members/me/series/order", consumes = "application/json")
    public ResponseEntity<Void> saveOrders(@AuthenticationPrincipal Jwt jwt,
            @RequestParam MultiValueMap<String,String> query, @RequestBody JsonNode body) {
        checkQuery(query, Set.of());
        series.saveOrders(memberId(jwt), PostSeriesOrderRequest.parse(body));
        return ResponseEntity.noContent().build();
    }
    @GetMapping("/api/v1/blogs/{handle}/series")
    public ApiResponse<List<SeriesView>> publicList(@PathVariable String handle, @RequestParam MultiValueMap<String,String> query) {
        checkQuery(query, Set.of());
        return ApiResponse.of(series.publicList(handle));
    }
    @GetMapping("/api/v1/blogs/{handle}/series/{id:[0-9]+}")
    public ApiResponse<SeriesView> publicDetail(@PathVariable String handle, @PathVariable String id, @RequestParam MultiValueMap<String,String> query) {
        checkQuery(query, Set.of());
        return ApiResponse.of(series.publicly(handle, id(id)));
    }
    @GetMapping("/api/v1/blogs/{handle}/series/{id:[0-9]+}/posts")
    public ApiResponse<PostPage> publicPosts(@PathVariable String handle, @PathVariable String id,
            @AuthenticationPrincipal Jwt jwt, @RequestParam MultiValueMap<String,String> query) {
        var page = pageable(query);
        series.publicly(handle, id(id));
        Long requester = jwt != null && "USER".equals(jwt.getClaimAsString("role")) ? memberId(jwt) : null;
        return ApiResponse.of(PostPage.from(likes.decorate(posts.listSeries(id(id), members.findPublicBlog(handle).getId(), false, page), requester), profileImages::url));
    }
    private long memberId(Jwt jwt) { return PostWriteRequest.id(jwt.getSubject().substring("member:".length()), "memberId"); }
    private long id(String id) { return PostWriteRequest.id(id, "seriesId"); }
    private PageRequest pageable(MultiValueMap<String,String> query) {
        checkQuery(query, Set.of("page", "size"));
        int page = number(query.getFirst("page"), 0), size = number(query.getFirst("size"), 20);
        if (size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) throw invalid();
        return PageRequest.of(page, size);
    }
    private int number(String value, int fallback) {
        if (value == null) return fallback;
        try { if (!value.matches("[0-9]+")) throw invalid(); return Integer.parseInt(value); }
        catch (NumberFormatException error) { throw invalid(); }
    }
    private void checkQuery(MultiValueMap<String,String> query, Set<String> allowed) {
        if (query.entrySet().stream().anyMatch(entry -> !allowed.contains(entry.getKey()) || entry.getValue().size() != 1)) throw invalid();
    }
    private ApplicationException invalid() { return new ApplicationException(GlobalErrorCode.INVALID_REQUEST); }
}
