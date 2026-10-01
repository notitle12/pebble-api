package com.pebble.api.project.domain;

import com.pebble.api.tag.domain.Tag;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "project_tag")
public class ProjectTag {
    @EmbeddedId
    private ProjectTagId id;
    @MapsId("projectId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private Project project;
    @MapsId("tagId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tag_id", nullable = false, updatable = false)
    private Tag tag;
    @Column(name = "display_order", nullable = false)
    private int displayOrder;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ProjectTag() { }
    public ProjectTag(Project project, Tag tag, int displayOrder) {
        this.project = project;
        this.tag = tag;
        this.displayOrder = displayOrder;
        this.id = new ProjectTagId(project.getId(), tag.getId());
    }
    @PrePersist void prePersist() { if (createdAt == null) createdAt = Instant.now(); }
    public Project getProject() { return project; }
    public Tag getTag() { return tag; }
    public int getDisplayOrder() { return displayOrder; }
}
