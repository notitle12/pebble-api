package com.pebble.api.post.presentation;

import com.pebble.api.global.media.MediaRequest;
import com.pebble.api.global.presentation.response.ApiResponse;
import com.pebble.api.post.application.PostBodyImageService;
import com.pebble.api.post.application.PostBodyImageService.ImageView;
import com.pebble.api.post.presentation.dto.PostWriteRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartHttpServletRequest;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/posts/{postId:[0-9]+}/images")
public class PostBodyImageController {
    private final PostBodyImageService images;
    @PostMapping(consumes="multipart/form-data")
    public ApiResponse<ImageView> upload(@PathVariable String postId,@AuthenticationPrincipal Jwt jwt,MultipartHttpServletRequest request) {
        return ApiResponse.of(images.upload(memberId(jwt),id(postId),MediaRequest.file(request,Set.of())));
    }
    @GetMapping
    public ApiResponse<List<ImageView>> list(@PathVariable String postId,@AuthenticationPrincipal Jwt jwt,HttpServletRequest request) throws IOException {
        noBody(request); return ApiResponse.of(images.list(memberId(jwt),id(postId)));
    }
    @GetMapping("/{imageId:[0-9]+}/content")
    public ResponseEntity<Void> content(@PathVariable String postId,@PathVariable String imageId,HttpServletRequest request) throws IOException {
        noBody(request);
        // 본문에는 이 경로를 저장하고 매 조회마다 공개 상태를 확인한 뒤 새 서명 URL을 발급한다.
        return ResponseEntity.status(302).cacheControl(CacheControl.noStore()).location(URI.create(images.publicUrl(id(postId),id(imageId)))).build();
    }
    @DeleteMapping("/{imageId:[0-9]+}")
    public ResponseEntity<Void> delete(@PathVariable String postId,@PathVariable String imageId,@AuthenticationPrincipal Jwt jwt,HttpServletRequest request) throws IOException {
        noBody(request); images.delete(memberId(jwt),id(postId),id(imageId)); return ResponseEntity.noContent().build();
    }
    private static void noBody(HttpServletRequest request) throws IOException {MediaRequest.noQuery(request);if(request.getInputStream().read()!=-1)throw MediaRequest.invalid();}
    private static long memberId(Jwt jwt){return Long.parseLong(jwt.getSubject().substring(7));}
    private static long id(String value){return PostWriteRequest.id(value,"id");}
}
