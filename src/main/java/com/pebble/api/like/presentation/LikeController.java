package com.pebble.api.like.presentation;

import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.like.application.PostLikeService;
import com.pebble.api.like.application.ProjectLikeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class LikeController {
    private final PostLikeService posts;
    private final ProjectLikeService projects;

    @PutMapping("/api/v1/posts/{postId:[0-9]+}/like")
    public ResponseEntity<Void> registerPost(@PathVariable String postId, @AuthenticationPrincipal Jwt jwt,
            @RequestParam MultiValueMap<String, String> query, @RequestBody(required = false) String body) {
        empty(query, body);
        posts.register(id(postId), memberId(jwt));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/v1/posts/{postId:[0-9]+}/like")
    public ResponseEntity<Void> cancelPost(@PathVariable String postId, @AuthenticationPrincipal Jwt jwt,
            @RequestParam MultiValueMap<String, String> query, @RequestBody(required = false) String body) {
        empty(query, body);
        posts.cancel(id(postId), memberId(jwt));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/v1/projects/{projectId:[0-9]+}/like")
    public ResponseEntity<Void> registerProject(@PathVariable String projectId, @AuthenticationPrincipal Jwt jwt,
            @RequestParam MultiValueMap<String, String> query, @RequestBody(required = false) String body) {
        empty(query, body);
        projects.register(id(projectId), memberId(jwt));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/v1/projects/{projectId:[0-9]+}/like")
    public ResponseEntity<Void> cancelProject(@PathVariable String projectId, @AuthenticationPrincipal Jwt jwt,
            @RequestParam MultiValueMap<String, String> query, @RequestBody(required = false) String body) {
        empty(query, body);
        projects.cancel(id(projectId), memberId(jwt));
        return ResponseEntity.noContent().build();
    }

    private long memberId(Jwt jwt) { return Long.parseLong(jwt.getSubject().substring("member:".length())); }
    private long id(String value) {
        try {
            if (!value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new ApplicationException(GlobalErrorCode.VALIDATION_ERROR, "id", "양의 10진 문자열 ID를 입력해 주세요.");
        }
    }
    private void empty(MultiValueMap<String, String> query, String body) {
        if (!query.isEmpty() || body != null) throw new ApplicationException(GlobalErrorCode.INVALID_REQUEST);
    }
}
