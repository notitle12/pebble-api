package com.pebble.api.global.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Transient;
import java.time.Instant;

/** 생성 시각만 공유한다. 식별자·소유권·삭제 정책은 각 엔티티가 소유한다. */
@MappedSuperclass
public abstract class BaseCreatedEntity {
    @Column(name = "created_at", nullable = false, updatable = false)
    protected Instant createdAt;

    // 같은 persist 이벤트의 초기 수정·게시 시각에도 동일한 기준 시각을 사용한다.
    @Transient
    private Instant creationTime;

    @PrePersist
    protected void initializeCreationTime() {
        creationTime = Instant.now();
        if (createdAt == null) createdAt = creationTime;
    }

    protected final Instant creationTime() { return creationTime; }
    public Instant getCreatedAt() { return createdAt; }
}
