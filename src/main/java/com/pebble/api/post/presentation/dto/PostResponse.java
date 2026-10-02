package com.pebble.api.post.presentation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pebble.api.category.domain.CategoryStatus;
import com.pebble.api.post.application.PostService.PostView;
import com.pebble.api.post.domain.BlockType;
import com.pebble.api.post.domain.CodeLanguage;
import com.pebble.api.post.domain.PostVisibility;
import com.pebble.api.tag.domain.TagStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;

public record PostResponse(String id, String postNumber, String slug, String urlKey, int displayOrder, Author author, String title, String summary,
                           @JsonInclude(JsonInclude.Include.NON_NULL) List<Block> blocks,
                           Classification category, List<Technology> tags,
                           String boardId, String projectId, String thumbnailUrl,
                           PostVisibility visibilityStatus, long likeCount, boolean likedByMe,
                           Instant publishedAt, Instant createdAt, Instant updatedAt,
                           @JsonInclude(JsonInclude.Include.NON_NULL) Boolean isBlocked) {
    public static PostResponse from(PostView view) {
        var post = view.post();
        var member = post.getAuthor();
        var category = post.getCategory();
        return new PostResponse(post.getId().toString(), Long.toString(post.getPostNumber()), post.getSlug(), post.getSlug() == null ? Long.toString(post.getPostNumber()) : post.getSlug(), post.getDisplayOrder(),
                new Author(member.getId().toString(), member.getHandle(), member.getBlogName(), member.getNickname(),
                member.getProfileImageUrl()), post.getTitle(), post.getSummary(),
                view.blocks() == null ? null : view.blocks().stream().map(block -> new Block(block.getType(),
                        block.getContent(), block.getLanguage(), block.getTitle(), block.getDisplayOrder())).toList(),
                category == null ? null : new Classification(category.getId().toString(), category.getName(),
                        category.getSlug(), category.getStatus()),
                view.tags().stream().map(tag -> new Technology(tag.getId().toString(), tag.getName(), tag.getSlug(), tag.getStatus())).toList(),
                post.getBoardId() == null ? null : post.getBoardId().toString(),
                post.getProjectId() == null ? null : post.getProjectId().toString(), view.thumbnailUrl(), post.getVisibility(), view.likeCount(), view.likedByMe(), post.getPublishedAt(), post.getCreatedAt(),
                post.getUpdatedAt(), view.owner() ? post.isBlocked() : null);
    }

    public record Author(String id, String handle, String blogName, String nickname, String profileImageUrl) { }
    public record Block(BlockType type, String content, CodeLanguage language, String title, int displayOrder) { }
    public record Classification(String id, String name, String slug, CategoryStatus status) { }
    public record Technology(String id, String name, String slug, TagStatus status) { }

    public record PostPage(List<PostResponse> content, int page, int size, long totalElements, int totalPages,
                           boolean hasNext, boolean hasPrevious) {
        public static PostPage from(Page<PostView> page) {
            return new PostPage(page.getContent().stream().map(PostResponse::from).toList(), page.getNumber(),
                    page.getSize(), page.getTotalElements(), page.getTotalPages(), page.hasNext(), page.hasPrevious());
        }
    }
}
