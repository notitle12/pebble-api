package com.pebble.api.comment.presentation.dto;

import com.pebble.api.comment.domain.Comment;
import com.pebble.api.comment.domain.CommentVisibility;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;

public record CommentResponse(String id, Author author, String body, CommentVisibility visibility,
                              Instant createdAt, Instant updatedAt) {
    public static CommentResponse from(Comment comment) {
        var member = comment.getAuthor();
        return new CommentResponse(comment.getId().toString(),
                new Author(member.getId().toString(), member.getHandle(), member.getBlogName(),
                        member.getNickname(), member.getProfileImageUrl()),
                comment.getBody(), comment.getVisibility(), comment.getCreatedAt(), comment.getUpdatedAt());
    }
    public record Author(String id, String handle, String blogName, String nickname, String profileImageUrl) { }
    public record CommentPage(List<CommentResponse> content, int page, int size, long totalElements, int totalPages,
                              boolean hasNext, boolean hasPrevious) {
        public static CommentPage from(Page<Comment> page) {
            return new CommentPage(page.getContent().stream().map(CommentResponse::from).toList(), page.getNumber(),
                    page.getSize(), page.getTotalElements(), page.getTotalPages(), page.hasNext(), page.hasPrevious());
        }
    }
}
