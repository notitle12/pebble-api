package com.pebble.api.member.presentation;

import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.global.presentation.response.ApiResponse;
import com.pebble.api.member.application.BlogFollowService;
import com.pebble.api.member.application.BlogFollowService.Direction;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class BlogFollowController {
    private static final String FOLLOW = "/api/v1/members/me/blogs/{handle}/follow";
    private final BlogFollowService follows;

    @GetMapping(FOLLOW)
    public ApiResponse<BlogFollowService.Status> status(@AuthenticationPrincipal Jwt jwt, @PathVariable String handle,
            HttpServletRequest request, @RequestBody(required = false) String body) {
        validateFollow(handle, request, body);
        return ApiResponse.of(follows.status(memberId(jwt), handle));
    }

    @PutMapping(FOLLOW)
    public ApiResponse<BlogFollowService.Status> follow(@AuthenticationPrincipal Jwt jwt, @PathVariable String handle,
            HttpServletRequest request, @RequestBody(required = false) String body) {
        validateFollow(handle, request, body);
        return ApiResponse.of(follows.change(memberId(jwt), handle, true));
    }

    @DeleteMapping(FOLLOW)
    public ApiResponse<BlogFollowService.Status> unfollow(@AuthenticationPrincipal Jwt jwt, @PathVariable String handle,
            HttpServletRequest request, @RequestBody(required = false) String body) {
        validateFollow(handle, request, body);
        return ApiResponse.of(follows.change(memberId(jwt), handle, false));
    }

    @GetMapping("/api/v1/members/me/blog-follows")
    public ApiResponse<BlogFollowService.FollowPage> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam String direction, @RequestParam(defaultValue = "0") String page,
            @RequestParam(defaultValue = "20") String size, HttpServletRequest request,
            @RequestBody(required = false) String body) {
        if (body != null || request.getParameterMap().entrySet().stream().anyMatch(entry ->
                !Set.of("direction", "page", "size").contains(entry.getKey()) || entry.getValue().length != 1)) {
            throw invalid();
        }
        var list = switch (direction) {
            case "followers" -> Direction.FOLLOWERS;
            case "following" -> Direction.FOLLOWING;
            default -> throw invalid();
        };
        int index = number(page), count = number(size);
        if (count < 1 || count > 50 || index > 10_000) throw invalid();
        return ApiResponse.of(follows.list(memberId(jwt), list, index, count));
    }

    private static long memberId(Jwt jwt) { return Long.parseLong(jwt.getSubject().substring("member:".length())); }

    private static void validateFollow(String handle, HttpServletRequest request, String body) {
        if (body != null || request.getQueryString() != null) throw invalid();
        if (!handle.matches("^[a-z][a-z0-9_-]{1,28}[a-z0-9_]$")) {
            throw new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    private static int number(String value) {
        if (!value.matches("[0-9]{1,5}")) throw invalid();
        return Integer.parseInt(value);
    }

    private static ApplicationException invalid() { return new ApplicationException(GlobalErrorCode.VALIDATION_ERROR); }
}
