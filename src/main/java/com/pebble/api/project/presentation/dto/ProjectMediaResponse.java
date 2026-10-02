package com.pebble.api.project.presentation.dto;

import com.pebble.api.project.application.ProjectMediaView;
import com.pebble.api.project.domain.MediaRole;
import java.time.Instant;

public record ProjectMediaResponse(String id, MediaRole mediaRole, String altText, int displayOrder,
                                   String url, String thumbnailUrl, Instant createdAt) {
    public static ProjectMediaResponse from(ProjectMediaView view) {
        return new ProjectMediaResponse(view.id(), view.mediaRole(), view.altText(), view.displayOrder(),
                view.url(), view.thumbnailUrl(), view.createdAt());
    }
}
