package com.pebble.api.admin.presentation.dto;

import com.pebble.api.project.application.ProjectService.ProjectView;
import com.pebble.api.project.presentation.dto.ProjectResponse;
import java.time.Instant;

public record AdminProjectResponse(ProjectResponse content, Instant blockedAt, String blockedByAdminId, Instant deletedAt) {
    public static AdminProjectResponse from(ProjectView view) {
        var project = view.project();
        return new AdminProjectResponse(ProjectResponse.from(view), project.getBlockedAt(),
                project.getBlockedByAdminId() == null ? null : project.getBlockedByAdminId().toString(), project.getDeletedAt());
    }
}
