package com.pebble.api.techstack.domain;

import com.pebble.api.global.persistence.BaseTimeEntity;
import com.pebble.api.global.id.TsidGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "tech_stack")
public class TechStack extends BaseTimeEntity {

    @Id
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, length = 100, unique = true)
    private String slug;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TechStackStatus status;

    protected TechStack() {
    }

    public TechStack(String name, String slug, Integer displayOrder, TechStackStatus status) {
        this.name = name;
        this.slug = slug;
        this.displayOrder = displayOrder;
        this.status = status;
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = TsidGenerator.generate();
        }
    }

    public void update(String name, String slug, int displayOrder, TechStackStatus status) {
        this.name = name;
        this.slug = slug;
        this.displayOrder = displayOrder;
        this.status = status;
    }

    public Long getId() {
        return id;
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

    public TechStackStatus getStatus() {
        return status;
    }
}
