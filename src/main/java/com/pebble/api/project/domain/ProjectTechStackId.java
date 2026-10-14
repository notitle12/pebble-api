package com.pebble.api.project.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class ProjectTechStackId implements Serializable {
    @Column(name = "project_id", nullable = false)
    private Long projectId;
    @Column(name = "tech_stack_id", nullable = false)
    private Long techStackId;
    protected ProjectTechStackId() { }
    public ProjectTechStackId(Long projectId, Long techStackId) { this.projectId = projectId; this.techStackId = techStackId; }
    public Long getProjectId() { return projectId; }
    public Long getTechStackId() { return techStackId; }
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        return other instanceof ProjectTechStackId that && Objects.equals(projectId, that.projectId) && Objects.equals(techStackId, that.techStackId);
    }
    @Override public int hashCode() { return Objects.hash(projectId, techStackId); }
}
