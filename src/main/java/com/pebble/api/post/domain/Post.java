package com.pebble.api.post.domain;

import com.pebble.api.category.domain.Category;
import com.pebble.api.global.id.TsidGenerator;
import com.pebble.api.member.domain.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Check;

@Entity
@Table(name = "post")
@Check(name = "ck_post_summary_length", constraints = "summary is null or char_length(summary) <= 500")
@Check(name = "ck_post_visibility", constraints = "visibility_status in ('PUBLIC', 'HIDDEN', 'DELETED')")
@Check(name = "ck_post_blocked_metadata", constraints = "((is_blocked = true and blocked_at is not null and blocked_by_admin_id is not null) or (is_blocked = false and blocked_at is null and blocked_by_admin_id is null))")
public class Post {

    @Id
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_member_id", nullable = false, updatable = false)
    private Member author;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(name = "board_id")
    private Long boardId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 200, updatable = false)
    private String slug;

    @Column(name = "post_number", nullable = false, updatable = false)
    private long postNumber;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility_status", nullable = false, length = 20)
    private PostVisibility visibility;

    @Column(name = "is_blocked", nullable = false)
    private boolean blocked;

    @Column(name = "blocked_at")
    private Instant blockedAt;

    @Column(name = "blocked_by_admin_id")
    private Long blockedByAdminId;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Post() {
    }

    public Post(Member author, Category category, String title, String summary, PostVisibility visibility, String slug, long postNumber) {
        if (visibility == PostVisibility.DELETED) {
            throw new IllegalArgumentException("Post cannot be created as deleted");
        }
        this.author = author;
        this.category = category;
        this.title = title;
        this.summary = summary;
        this.visibility = visibility;
        if (postNumber <= 0) throw new IllegalArgumentException("Post number must be positive");
        this.slug = slug;
        this.postNumber = postNumber;
        if (visibility == PostVisibility.PUBLIC) {
            this.publishedAt = Instant.now();
        }
    }

    public void updateContent(String title, String summary, Category category) {
        ensureNotDeleted();
        this.title = title;
        this.summary = summary;
        this.category = category;
        this.updatedAt = Instant.now();
    }

    public void changeVisibility(PostVisibility visibility) {
        ensureNotDeleted();
        if (visibility == PostVisibility.DELETED) {
            throw new IllegalArgumentException("Use delete to delete a post");
        }
        this.visibility = visibility;
        if (visibility == PostVisibility.PUBLIC && publishedAt == null) {
            publishedAt = Instant.now();
        }
    }

    public void delete() {
        ensureNotDeleted();
        visibility = PostVisibility.DELETED;
        deletedAt = Instant.now();
    }

    public void changeBoard(Long boardId) {
        ensureNotDeleted();
        this.boardId = boardId;
    }

    public void changeOrder(int displayOrder) {
        ensureNotDeleted();
        if (displayOrder < 0) throw new IllegalArgumentException("Display order must not be negative");
        this.displayOrder = displayOrder;
    }

    private void ensureNotDeleted() {
        if (visibility == PostVisibility.DELETED) {
            throw new IllegalStateException("Deleted post cannot be changed");
        }
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (id == null) {
            id = TsidGenerator.generate();
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (visibility == PostVisibility.PUBLIC && publishedAt == null) {
            publishedAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Member getAuthor() { return author; }
    public Category getCategory() { return category; }
    public Long getBoardId() { return boardId; }
    public String getTitle() { return title; }
    public String getSlug() { return slug; }
    public long getPostNumber() { return postNumber; }
    public int getDisplayOrder() { return displayOrder; }
    public String getSummary() { return summary; }
    public PostVisibility getVisibility() { return visibility; }
    public boolean isBlocked() { return blocked; }
    public Instant getBlockedAt() { return blockedAt; }
    public Long getBlockedByAdminId() { return blockedByAdminId; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
