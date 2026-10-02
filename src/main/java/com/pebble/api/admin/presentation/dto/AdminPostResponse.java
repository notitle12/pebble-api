package com.pebble.api.admin.presentation.dto;

import com.pebble.api.post.application.PostService.PostView;
import com.pebble.api.post.presentation.dto.PostResponse;
import java.time.Instant;

public record AdminPostResponse(PostResponse content, Instant blockedAt, String blockedByAdminId, Instant deletedAt) {
    public static AdminPostResponse from(PostView view) {
        var post = view.post();
        return new AdminPostResponse(PostResponse.from(view), post.getBlockedAt(),
                post.getBlockedByAdminId() == null ? null : post.getBlockedByAdminId().toString(), post.getDeletedAt());
    }
}
