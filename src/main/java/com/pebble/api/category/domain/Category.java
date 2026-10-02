package com.pebble.api.category.domain;

import com.pebble.api.global.id.TsidGenerator;
import com.pebble.api.global.exception.ApplicationException;
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

@Entity
@Table(name = "category")
public class Category {

    @Id
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, length = 100, unique = true)
    private String slug;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CategoryStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Category() {
    }

    public Category(Category parent, String name, String slug, Integer displayOrder, CategoryStatus status) {
        if (parent != null && parent.getParent() != null) {
            throw new IllegalArgumentException("Category depth cannot exceed two levels");
        }
        this.parent = parent;
        this.name = name;
        this.slug = slug;
        this.displayOrder = displayOrder;
        this.status = status;
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
    }

    public void update(Category parent, String name, String slug, int displayOrder, CategoryStatus status) {
        if (parent != null && (parent == this || parent.getParent() != null)) {
            throw new ApplicationException(CategoryError.CATEGORY_HIERARCHY_CONFLICT);
        }
        this.parent = parent;
        this.name = name;
        this.slug = slug;
        this.displayOrder = displayOrder;
        this.status = status;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Category getParent() {
        return parent;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public Integer getDisplayOrder() {
        return displayOrder;
    }

    public CategoryStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
