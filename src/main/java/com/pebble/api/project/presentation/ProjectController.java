package com.pebble.api.project.presentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.global.presentation.response.ApiResponse;
import com.pebble.api.like.application.ProjectLikeService;
import com.pebble.api.project.application.ProjectService;
import com.pebble.api.project.domain.ProjectLifecycleStatus;
import com.pebble.api.project.domain.ProjectVisibility;
import com.pebble.api.project.presentation.dto.ProjectResponse;
import com.pebble.api.project.presentation.dto.ProjectResponse.ProjectPage;
import com.pebble.api.project.presentation.dto.ProjectWriteRequest;
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
public class ProjectController {
    private final com.pebble.api.member.application.MemberProfileImages profileImages;
    private final ProjectService projects;
    private final ProjectLikeService likes;

    @GetMapping("/api/v1/projects/search")
    public ApiResponse<ProjectPage> search(@RequestParam MultiValueMap<String, String> query, @AuthenticationPrincipal Jwt jwt) {
        checkQuery(query, Set.of("q", "page", "size", "sort", "tagId", "lifecycleStatus"));
        return ApiResponse.of(page(likes.decorate(projects.search(ProjectWriteRequest.searchTerm(query.getFirst("q")),
                optionalId(query, "tagId"), lifecycle(query), pageable(query)), requesterId(jwt))));
    }

    @GetMapping("/api/v1/members/{memberId:[0-9]+}/projects")
    public ApiResponse<ProjectPage> member(@PathVariable String memberId, @RequestParam MultiValueMap<String, String> query,
                                           @AuthenticationPrincipal Jwt jwt) {
        checkQuery(query, Set.of("page", "size", "sort", "tagId", "lifecycleStatus"));
        return ApiResponse.of(page(likes.decorate(projects.listMember(ProjectWriteRequest.id(memberId, "memberId"),
                optionalId(query, "tagId"), lifecycle(query), pageable(query)), requesterId(jwt))));
    }

    @GetMapping("/api/v1/members/me/projects")
    public ApiResponse<ProjectPage> mine(@AuthenticationPrincipal Jwt jwt, @RequestParam MultiValueMap<String, String> query) {
        checkQuery(query, Set.of("page", "size", "sort", "visibilityStatus"));
        ProjectVisibility visibility = null;
        if (query.containsKey("visibilityStatus")) {
            try {
                visibility = ProjectVisibility.valueOf(query.getFirst("visibilityStatus"));
                if (visibility == ProjectVisibility.DELETED) throw new IllegalArgumentException();
            } catch (RuntimeException exception) {
                throw invalid("visibilityStatus");
            }
        }
        return ApiResponse.of(page(likes.decorate(projects.listMine(memberId(jwt), visibility, pageable(query)), requesterId(jwt))));
    }

    @PostMapping(value = "/api/v1/projects", consumes = "application/json")
    public ResponseEntity<ApiResponse<ProjectResponse>> create(@AuthenticationPrincipal Jwt jwt,
                                                                @RequestBody JsonNode request) {
        ProjectResponse response = response(likes.decorate(
                projects.create(memberId(jwt), ProjectWriteRequest.parse(request, true)), requesterId(jwt)));
        return ResponseEntity.created(org.springframework.web.util.UriComponentsBuilder.fromPath("/api/v1/projects/{id}")
                .buildAndExpand(response.id()).encode().toUri()).body(ApiResponse.of(response));
    }

    @GetMapping("/api/v1/projects")
    public ApiResponse<ProjectPage> list(@RequestParam MultiValueMap<String, String> query, @AuthenticationPrincipal Jwt jwt) {
        checkQuery(query, Set.of("page", "size", "sort", "tagId", "lifecycleStatus"));
        return ApiResponse.of(page(likes.decorate(
                projects.listPublic(optionalId(query, "tagId"), lifecycle(query), pageable(query)), requesterId(jwt))));
    }

    private Long optionalId(MultiValueMap<String, String> query, String field) {
        return query.containsKey(field) ? ProjectWriteRequest.id(query.getFirst(field), field) : null;
    }

    private ProjectLifecycleStatus lifecycle(MultiValueMap<String, String> query) {
        ProjectLifecycleStatus lifecycle = null;
        if (query.containsKey("lifecycleStatus")) {
            try {
                lifecycle = ProjectLifecycleStatus.valueOf(query.getFirst("lifecycleStatus"));
            } catch (RuntimeException exception) {
                throw invalid("lifecycleStatus");
            }
        }
        return lifecycle;
    }

    @GetMapping("/api/v1/projects/{projectId:[0-9]+}")
    public ApiResponse<ProjectResponse> detail(@PathVariable String projectId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of(response(likes.decorate(projects.detail(ProjectWriteRequest.id(projectId, "projectId"),
                jwt == null ? null : memberId(jwt)), requesterId(jwt))));
    }

    @PatchMapping(value = "/api/v1/projects/{projectId:[0-9]+}", consumes = "application/json")
    public ApiResponse<ProjectResponse> update(@PathVariable String projectId, @AuthenticationPrincipal Jwt jwt,
                                                @RequestBody JsonNode request) {
        return ApiResponse.of(response(likes.decorate(projects.update(ProjectWriteRequest.id(projectId, "projectId"),
                memberId(jwt), ProjectWriteRequest.parse(request, false)), requesterId(jwt))));
    }

    @DeleteMapping("/api/v1/projects/{projectId:[0-9]+}")
    public ResponseEntity<Void> delete(@PathVariable String projectId, @AuthenticationPrincipal Jwt jwt) {
        projects.delete(ProjectWriteRequest.id(projectId, "projectId"), memberId(jwt));
        return ResponseEntity.noContent().build();
    }

    private long memberId(Jwt jwt) { return Long.parseLong(jwt.getSubject().substring("member:".length())); }

    private Long requesterId(Jwt jwt) {
        return jwt != null && "USER".equals(jwt.getClaimAsString("role")) ? memberId(jwt) : null;
    }

    private PageRequest pageable(MultiValueMap<String, String> query) {
        int page = integer(query.getFirst("page"), 0, "page");
        int size = integer(query.getFirst("size"), 20, "size");
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) throw invalid("page/size");
        String value = query.getFirst("sort");
        String[] parts = value == null ? new String[]{"publishedAt", "desc"} : value.split(",", -1);
        if (parts.length != 2 || !Set.of("publishedAt", "createdAt").contains(parts[0])
                || !Set.of("asc", "desc").contains(parts[1])) throw invalid("sort");
        return PageRequest.of(page, size, Sort.by(Sort.Direction.fromString(parts[1]), parts[0], "id"));
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
    private ProjectResponse response(com.pebble.api.project.application.ProjectService.ProjectView view) { return ProjectResponse.from(view, profileImages::url); }
    private ProjectPage page(org.springframework.data.domain.Page<com.pebble.api.project.application.ProjectService.ProjectView> view) { return ProjectPage.from(view, profileImages::url); }

}
