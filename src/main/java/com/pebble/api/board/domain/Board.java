package com.pebble.api.board.domain;

import com.pebble.api.global.id.TsidGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "board")
public class Board {
    @Id
    private Long id;
    @Column(name = "owner_member_id", nullable = false, updatable = false)
    private Long ownerMemberId;
    @Column(name = "parent_id")
    private Long parentId;
    @Column(nullable = false, length = 50)
    private String name;
    @Column(name = "display_order", nullable = false)
    private int displayOrder;
    @Column(name = "deleted_at")
    private Instant deletedAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Board() { }

    public Board(long ownerMemberId, String name, Long parentId, int displayOrder) {
        this.ownerMemberId = ownerMemberId;
        update(name, parentId, displayOrder);
    }

    public void update(String name, Long parentId, int displayOrder) {
        if (deletedAt != null) throw new IllegalStateException("Deleted board cannot be changed");
        if (name == null || name.isBlank() || name.codePointCount(0, name.length()) > 50 || displayOrder < 0) {
            throw new IllegalArgumentException("Invalid board name or order");
        }
        this.name = name;
        this.parentId = parentId;
        this.displayOrder = displayOrder;
    }

    public void delete() {
        if (deletedAt != null) throw new IllegalStateException("Deleted board cannot be changed");
        deletedAt = Instant.now();
    }

    @PrePersist
    void prePersist() {
        if (id == null) id = TsidGenerator.generate();
        if (createdAt == null) createdAt = Instant.now();
        updatedAt = createdAt;
    }
    @PreUpdate
    void preUpdate() { updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public Long getOwnerMemberId() { return ownerMemberId; }
    public Long getParentId() { return parentId; }
    public String getName() { return name; }
    public int getDisplayOrder() { return displayOrder; }
    public Instant getDeletedAt() { return deletedAt; }
}
