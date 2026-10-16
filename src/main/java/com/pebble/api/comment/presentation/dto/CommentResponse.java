package com.pebble.api.comment.presentation.dto;

import com.pebble.api.comment.domain.Comment;
import com.pebble.api.comment.domain.CommentVisibility;
import com.pebble.api.comment.application.CommentView;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;

public record CommentResponse(String id, Author author, String body, CommentVisibility visibility, boolean locked,
                              Instant createdAt, Instant updatedAt) {
    public static CommentResponse from(Comment comment) { return from(comment, com.pebble.api.member.domain.Member::getProfileImageUrl); }

    public static CommentResponse from(Comment comment, java.util.function.Function<com.pebble.api.member.domain.Member, String> imageUrl) {
        return from(new CommentView(comment, false), imageUrl);
    }

    public static CommentResponse from(CommentView view,
            java.util.function.Function<com.pebble.api.member.domain.Member, String> imageUrl) {
        Comment comment = view.comment();
        var member = comment.getAuthor();
        var author = member == null
                ? new Author(null, null, null, comment.getGuestNickname(), null)
                : new Author(member.getId().toString(), member.getHandle(), member.getBlogName(),
                        member.getNickname(), imageUrl.apply(member));
        return new CommentResponse(comment.getId().toString(),
                author,
                view.locked() ? "비밀 댓글입니다." : comment.getBody(), comment.getVisibility(), view.locked(),
                comment.getCreatedAt(), comment.getUpdatedAt());
    }
    public record Author(String id, String handle, String blogName, String nickname, String profileImageUrl) { }
    public record CommentPage(List<CommentResponse> content, int page, int size, long totalElements, int totalPages,
                              boolean hasNext, boolean hasPrevious) {
        public static CommentPage from(Page<CommentView> page) { return from(page, com.pebble.api.member.domain.Member::getProfileImageUrl); }

        public static CommentPage from(Page<CommentView> page, java.util.function.Function<com.pebble.api.member.domain.Member, String> imageUrl) {
            return new CommentPage(page.getContent().stream().map(item -> CommentResponse.from(item, imageUrl)).toList(), page.getNumber(),
                    page.getSize(), page.getTotalElements(), page.getTotalPages(), page.hasNext(), page.hasPrevious());
        }
    }
}
