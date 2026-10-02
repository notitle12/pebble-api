package com.pebble.api.admin.presentation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pebble.api.comment.domain.Comment;
import com.pebble.api.comment.domain.CommentVisibility;
import com.pebble.api.comment.presentation.dto.CommentResponse;
import java.time.Instant;

public record AdminCommentResponse(String id, String contentId, CommentResponse.Author author,
        @JsonInclude(JsonInclude.Include.NON_NULL) String body, CommentVisibility visibility,
        Instant createdAt, Instant updatedAt, Instant deletedAt) {
    public static AdminCommentResponse from(Comment comment) {
        var member = comment.getAuthor();
        return new AdminCommentResponse(comment.getId().toString(), Long.toString(comment.getContentId()),
                new CommentResponse.Author(member.getId().toString(), member.getHandle(), member.getBlogName(), member.getNickname(), member.getProfileImageUrl()),
                comment.getDeletedAt() == null ? comment.getBody() : null, comment.getVisibility(),
                comment.getCreatedAt(), comment.getUpdatedAt(), comment.getDeletedAt());
    }
}
