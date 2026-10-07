package com.pebble.api.project.domain;

import com.pebble.api.global.persistence.BaseCreatedEntity;
import com.pebble.api.global.id.TsidGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "project_link")
public class ProjectLink extends BaseCreatedEntity {
    @Id
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private Project project;
    @Enumerated(EnumType.STRING)
    @Column(name = "link_type", nullable = false, length = 20)
    private ProjectLinkType type;
    @Column(length = 100)
    private String label;
    @Column(nullable = false, length = 2048)
    private String url;
    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    protected ProjectLink() { }
    public ProjectLink(Project project, ProjectLinkType type, String label, String url, int displayOrder) {
        this.project = project;
        this.type = type;
        this.label = label;
        this.url = url;
        this.displayOrder = displayOrder;
    }
    @PrePersist void prePersist() {
        if (id == null) id = TsidGenerator.generate();
    }
    public Long getId() { return id; }
    public Project getProject() { return project; }
    public ProjectLinkType getType() { return type; }
    public String getLabel() { return label; }
    public String getUrl() { return url; }
    public int getDisplayOrder() { return displayOrder; }
}
