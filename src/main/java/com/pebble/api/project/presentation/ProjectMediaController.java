package com.pebble.api.project.presentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.global.media.MediaRequest;
import com.pebble.api.global.presentation.response.ApiResponse;
import com.pebble.api.project.application.ProjectMediaService;
import com.pebble.api.project.domain.MediaRole;
import com.pebble.api.project.presentation.dto.ProjectMediaResponse;
import com.pebble.api.project.presentation.dto.ProjectWriteRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/projects/{projectId:[0-9]+}/media")
public class ProjectMediaController {
    private final ProjectMediaService media;
    private final ObjectMapper mapper;

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<ProjectMediaResponse>> upload(
            @PathVariable String projectId,
            @AuthenticationPrincipal Jwt jwt,
            MultipartHttpServletRequest request) {
        byte[] bytes = MediaRequest.file(request, Set.of("mediaRole", "altText", "displayOrder"));
        MediaRole role;
        try {
            role = MediaRole.valueOf(request.getParameter("mediaRole"));
        } catch (RuntimeException exception) {
            throw MediaRequest.invalid();
        }
        var view = media.upload(memberId(jwt), id(projectId), bytes, role,
                MediaRequest.text(request.getParameter("altText")),
                MediaRequest.order(request.getParameter("displayOrder")));
        return ResponseEntity.created(java.net.URI.create(
                        "/api/v1/projects/" + projectId + "/media/" + view.id()))
                .body(ApiResponse.of(ProjectMediaResponse.from(view)));
    }

    @PatchMapping(value = "/{mediaId:[0-9]+}", consumes = "application/json")
    public ApiResponse<ProjectMediaResponse> update(
            @PathVariable String projectId,
            @PathVariable String mediaId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody(required = false) String raw,
            HttpServletRequest request) {
        MediaRequest.noQuery(request);
        JsonNode body;
        try {
            body = mapper.reader().with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(raw == null ? "" : raw);
        } catch (IOException exception) {
            throw MediaRequest.invalid();
        }
        if (body == null) throw MediaRequest.invalid();
        if (!body.isObject()) throw MediaRequest.invalid();
        body.fieldNames().forEachRemaining(field -> {
            if (!Set.of("altText", "displayOrder").contains(field)) throw MediaRequest.invalid();
        });
        String alt = null;
        if (body.hasNonNull("altText")) {
            if (!body.get("altText").isTextual()) throw MediaRequest.invalid();
            alt = MediaRequest.text(body.get("altText").textValue());
        }
        Integer order = null;
        if (body.has("displayOrder")) {
            var value = body.get("displayOrder");
            if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
                throw MediaRequest.invalid();
            }
            order = value.intValue();
        }
        return ApiResponse.of(ProjectMediaResponse.from(media.update(
                memberId(jwt), id(projectId), id(mediaId), body.has("altText"), alt, order)));
    }

    @DeleteMapping("/{mediaId:[0-9]+}")
    public ResponseEntity<Void> delete(
            @PathVariable String projectId,
            @PathVariable String mediaId,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) throws IOException {
        MediaRequest.noQuery(request);
        if (request.getInputStream().read() != -1) throw MediaRequest.invalid();
        media.delete(memberId(jwt), id(projectId), id(mediaId));
        return ResponseEntity.noContent().build();
    }

    private long id(String value) {
        return ProjectWriteRequest.id(value, "id");
    }

    private long memberId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject().substring(7));
    }
}
