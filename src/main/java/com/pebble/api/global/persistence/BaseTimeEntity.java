package com.pebble.api.global.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.time.Instant;

/** 엔티티 변경 시각을 공유한다. JPQL bulk update는 호출자가 시각을 갱신해야 한다. */
@MappedSuperclass
public abstract class BaseTimeEntity extends BaseCreatedEntity {
    @Column(name = "updated_at", nullable = false)
    protected Instant updatedAt;

    @PrePersist
    protected void initializeModificationTime() {
        if (updatedAt == null) updatedAt = creationTime();
    }

    @PreUpdate
    protected void updateModificationTime() { updatedAt = Instant.now(); }

    public Instant getUpdatedAt() { return updatedAt; }
}
