package com.pebble.api.project.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class ProjectTagId implements Serializable {
    @Column(name = "project_id", nullable = false)
    private Long projectId;
    @Column(name = "tag_id", nullable = false)
    private Long tagId;
    protected ProjectTagId() { }
    public ProjectTagId(Long projectId, Long tagId) { this.projectId = projectId; this.tagId = tagId; }
    public Long getProjectId() { return projectId; }
    public Long getTagId() { return tagId; }
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        return other instanceof ProjectTagId that && Objects.equals(projectId, that.projectId) && Objects.equals(tagId, that.tagId);
    }
    @Override public int hashCode() { return Objects.hash(projectId, tagId); }
}
