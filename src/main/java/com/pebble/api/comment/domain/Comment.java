package com.pebble.api.comment.domain;

import com.pebble.api.global.persistence.BaseTimeEntity;
import com.pebble.api.global.id.TsidGenerator;
import com.pebble.api.global.exception.ApplicationException;
import com.pebble.api.global.exception.GlobalErrorCode;
import com.pebble.api.member.domain.Member;
import jakarta.persistence.*;
import java.time.Instant;

@MappedSuperclass
public abstract class Comment extends BaseTimeEntity {
    @Id
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "author_member_id", nullable = true, updatable = false)
    private Member author;
    @Column(name = "guest_nickname", length = 30)
    private String guestNickname;
    @Column(name = "guest_password_hash", length = 255)
    private String guestPasswordHash;
    @Column(nullable = false, columnDefinition = "text")
    private String body;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CommentVisibility visibility;
    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Comment() { }
    protected Comment(Member author, String body, CommentVisibility visibility) {
        this.author = author;
        this.body = body;
        this.visibility = visibility;
    }
    protected Comment(String guestNickname, String guestPasswordHash, String body,
                     CommentVisibility visibility) {
        this.guestNickname = guestNickname;
        this.guestPasswordHash = guestPasswordHash;
        this.body = body;
        this.visibility = visibility;
    }

    public void update(String body, CommentVisibility visibility) {
        ensureNotDeleted();
        this.body = body;
        this.visibility = visibility;
    }

    public void delete() {
        ensureNotDeleted();
        // 삭제 본문은 운영 응답이나 후속 조회에서도 복구할 수 없도록 제거한다.
        body = "";
        if (author == null) {
            guestNickname = "삭제된 방문자";
            guestPasswordHash = null;
        }
        deletedAt = Instant.now();
    }

    private void ensureNotDeleted() {
        if (deletedAt != null) throw new ApplicationException(GlobalErrorCode.RESOURCE_NOT_FOUND);
    }

    @PrePersist
    void initialize() {
        id = TsidGenerator.generate();
        createdAt = creationTime();
        updatedAt = createdAt;
    }

    public abstract long getContentId();
    public Long getId() { return id; }
    public Member getAuthor() { return author; }
    public boolean isGuest() { return author == null; }
    public String getGuestNickname() { return guestNickname; }
    public String getGuestPasswordHash() { return guestPasswordHash; }
    public String getBody() { return body; }
    public CommentVisibility getVisibility() { return visibility; }
    public Instant getDeletedAt() { return deletedAt; }
}
