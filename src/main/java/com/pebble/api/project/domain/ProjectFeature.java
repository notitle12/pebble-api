package com.pebble.api.project.domain;

import com.pebble.api.global.persistence.BaseTimeEntity;
import com.pebble.api.global.id.TsidGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "project_feature")
public class ProjectFeature extends BaseTimeEntity {
    @Id
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private Project project;
    @Column(nullable = false, length = 100)
    private String title;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;
    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    protected ProjectFeature() { }
    public ProjectFeature(Project project, String title, String description, int displayOrder) {
        this.project = project;
        this.title = title;
        this.description = description;
        this.displayOrder = displayOrder;
    }
    @PrePersist void prePersist() {
        if (id == null) id = TsidGenerator.generate();
    }
    public Long getId() { return id; }
    public Project getProject() { return project; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public int getDisplayOrder() { return displayOrder; }
}
