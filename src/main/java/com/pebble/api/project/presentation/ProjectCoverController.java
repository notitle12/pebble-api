package com.pebble.api.project.presentation;

import com.pebble.api.global.media.MediaRequest;
import com.pebble.api.global.presentation.response.ApiResponse;
import com.pebble.api.project.application.ProjectMediaService;
import com.pebble.api.project.presentation.dto.ProjectMediaResponse;
import com.pebble.api.project.presentation.dto.ProjectWriteRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;

@RestController
@RequiredArgsConstructor
public class ProjectCoverController {
    private final ProjectMediaService media;

    @PutMapping(value="/api/v1/projects/{projectId:[0-9]+}/cover",consumes="multipart/form-data")
    public ApiResponse<ProjectMediaResponse> cover(@PathVariable String projectId,@AuthenticationPrincipal Jwt jwt,MultipartHttpServletRequest request){
        byte[] bytes=MediaRequest.file(request,java.util.Set.of());
        return ApiResponse.of(ProjectMediaResponse.from(media.replaceCover(Long.parseLong(jwt.getSubject().substring("member:".length())),ProjectWriteRequest.id(projectId,"projectId"),bytes)));
    }

    @DeleteMapping("/api/v1/projects/{projectId:[0-9]+}/cover")
    public ResponseEntity<Void> deleteCover(@PathVariable String projectId, @AuthenticationPrincipal Jwt jwt) {
        long memberId = Long.parseLong(jwt.getSubject().substring("member:".length()));
        long pid = ProjectWriteRequest.id(projectId, "projectId");
        media.deleteCover(memberId, pid);
        return ResponseEntity.noContent().build();
    }
}
