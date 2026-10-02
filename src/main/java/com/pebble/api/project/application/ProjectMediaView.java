package com.pebble.api.project.application;

import com.pebble.api.project.domain.MediaRole;
import com.pebble.api.project.domain.ProjectMedia;
import java.time.Instant;

public record ProjectMediaView(String id, MediaRole mediaRole, String altText, int displayOrder,
                               String url, String thumbnailUrl, Instant createdAt) {
    public static ProjectMediaView from(ProjectMedia media, String url, String thumbnailUrl) {
        return new ProjectMediaView(media.getId().toString(), media.getMediaRole(), media.getAltText(),
                media.getDisplayOrder(), url, thumbnailUrl, media.getCreatedAt());
    }
}
