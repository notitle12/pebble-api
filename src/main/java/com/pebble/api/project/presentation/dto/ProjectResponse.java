package com.pebble.api.project.presentation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pebble.api.project.application.ProjectService.ProjectView;
import com.pebble.api.project.domain.ProjectLifecycleStatus;
import com.pebble.api.project.domain.ProjectLinkType;
import com.pebble.api.project.domain.ProjectVisibility;
import com.pebble.api.tag.domain.TagStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Page;

public record ProjectResponse(String id, Owner owner, String name, String summary,
                              @JsonInclude(JsonInclude.Include.NON_NULL) String description,
                              @JsonInclude(JsonInclude.Include.NON_NULL) String architectureDescription,
                              @JsonInclude(JsonInclude.Include.NON_NULL) String executionInstructions,
                              ProjectLifecycleStatus lifecycleStatus, LocalDate startedOn, LocalDate completedOn,
                              List<Technology> tags,
                              @JsonInclude(JsonInclude.Include.NON_NULL) List<Feature> features,
                              @JsonInclude(JsonInclude.Include.NON_NULL) List<Link> links,
                              @JsonInclude(JsonInclude.Include.NON_NULL) List<ProjectMediaResponse> media,
                              ProjectVisibility visibilityStatus, long likeCount, boolean likedByMe,
                              Instant publishedAt, Instant createdAt, Instant updatedAt,
                              @JsonInclude(JsonInclude.Include.NON_NULL) Boolean isBlocked) {
    public static ProjectResponse from(ProjectView view) {
        var project = view.project();
        var owner = project.getOwner();
        return new ProjectResponse(project.getId().toString(), new Owner(owner.getId().toString(), owner.getHandle(),
                owner.getBlogName(), owner.getNickname(), owner.getProfileImageUrl()), project.getName(), project.getSummary(),
                view.detail() ? project.getDescription() : null,
                view.detail() ? project.getArchitectureDescription() : null,
                view.detail() ? project.getExecutionInstructions() : null,
                project.getLifecycleStatus(), project.getStartedOn(), project.getCompletedOn(),
                view.tags().stream().map(tag -> new Technology(tag.getId().toString(), tag.getName(), tag.getSlug(),
                        tag.getStatus())).toList(),
                view.features() == null ? null : view.features().stream().map(feature -> new Feature(feature.getId().toString(),
                        feature.getTitle(), feature.getDescription(), feature.getDisplayOrder())).toList(),
                view.links() == null ? null : view.links().stream().map(link -> new Link(link.getId().toString(),
                        link.getType(), link.getLabel(), link.getUrl(), link.getDisplayOrder())).toList(),
                view.media() == null ? null : view.media().stream().map(ProjectMediaResponse::from).toList(), project.getVisibility(), view.likeCount(), view.likedByMe(), project.getPublishedAt(),
                project.getCreatedAt(), project.getUpdatedAt(), view.owner() ? project.isBlocked() : null);
    }

    public record Owner(String id, String handle, String blogName, String nickname, String profileImageUrl) { }
    public record Technology(String id, String name, String slug, TagStatus status) { }
    public record Feature(String id, String title, String description, int displayOrder) { }
    public record Link(String id, ProjectLinkType linkType, String label, String url, int displayOrder) { }

    public record ProjectPage(List<ProjectResponse> content, int page, int size, long totalElements, int totalPages,
                              boolean hasNext, boolean hasPrevious) {
        public static ProjectPage from(Page<ProjectView> page) {
            return new ProjectPage(page.getContent().stream().map(ProjectResponse::from).toList(), page.getNumber(),
                    page.getSize(), page.getTotalElements(), page.getTotalPages(), page.hasNext(), page.hasPrevious());
        }
    }
}
