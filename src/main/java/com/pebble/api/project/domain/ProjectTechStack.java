package com.pebble.api.project.domain;

import com.pebble.api.global.persistence.BaseCreatedEntity;
import com.pebble.api.techstack.domain.TechStack;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

@Entity
@Table(name = "project_tech_stack")
public class ProjectTechStack extends BaseCreatedEntity {
    @EmbeddedId
    private ProjectTechStackId id;
    @MapsId("projectId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private Project project;
    @MapsId("techStackId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tech_stack_id", nullable = false, updatable = false)
    private TechStack techStack;
    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    protected ProjectTechStack() { }
    public ProjectTechStack(Project project, TechStack techStack, int displayOrder) {
        this.project = project;
        this.techStack = techStack;
        this.displayOrder = displayOrder;
        this.id = new ProjectTechStackId(project.getId(), techStack.getId());
    }
    public Project getProject() { return project; }
    public TechStack getTechStack() { return techStack; }
    public int getDisplayOrder() { return displayOrder; }
}
