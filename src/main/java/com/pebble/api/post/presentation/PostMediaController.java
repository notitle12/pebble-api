package com.pebble.api.post.presentation;

import com.pebble.api.global.media.MediaRequest;
import com.pebble.api.global.presentation.response.ApiResponse;
import com.pebble.api.post.application.PostMediaService;
import com.pebble.api.post.presentation.dto.PostWriteRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/posts/{postId:[0-9]+}/thumbnail")
public class PostMediaController {
    private final PostMediaService media;

    @PutMapping(consumes = "multipart/form-data")
    public ApiResponse<ThumbnailResponse> upload(
            @PathVariable String postId,
            @AuthenticationPrincipal Jwt jwt,
            MultipartHttpServletRequest request) {
        byte[] bytes = MediaRequest.file(request, Set.of());
        return ApiResponse.of(new ThumbnailResponse(
                media.upload(memberId(jwt), PostWriteRequest.id(postId, "postId"), bytes)));
    }
    @DeleteMapping
    public ResponseEntity<Void> delete(
            @PathVariable String postId,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) throws IOException {
        MediaRequest.noQuery(request);
        if (request.getInputStream().read() != -1) throw MediaRequest.invalid();
        media.delete(memberId(jwt), PostWriteRequest.id(postId, "postId"));
        return ResponseEntity.noContent().build();
    }

    private long memberId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject().substring(7));
    }

    public record ThumbnailResponse(String thumbnailUrl) { }
}
