package com.pebble.api.project.domain;

import com.pebble.api.global.persistence.BaseTimeEntity;
import com.pebble.api.global.id.TsidGenerator;
import com.pebble.api.member.domain.Member;
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
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.Check;

@Entity
@Table(name = "project")
@Check(name = "ck_project_summary_length", constraints = "summary is null or char_length(summary) <= 500")
@Check(name = "ck_project_description_length", constraints = "description is null or char_length(description) <= 20000")
@Check(name = "ck_project_architecture_length", constraints = "architecture_description is null or char_length(architecture_description) <= 20000")
@Check(name = "ck_project_execution_length", constraints = "execution_instructions is null or char_length(execution_instructions) <= 10000")
@Check(name = "ck_project_lifecycle", constraints = "lifecycle_status in ('IN_PROGRESS', 'COMPLETED')")
@Check(name = "ck_project_visibility", constraints = "visibility_status in ('PUBLIC', 'HIDDEN', 'DELETED')")
@Check(name = "ck_project_blocked_metadata", constraints = "((is_blocked = true and blocked_at is not null and blocked_by_admin_id is not null) or (is_blocked = false and blocked_at is null and blocked_by_admin_id is null))")
public class Project extends BaseTimeEntity {
    @Id
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_member_id", nullable = false, updatable = false)
    private Member owner;
    @Column(nullable = false, length = 120)
    private String name;
    @Column(columnDefinition = "TEXT")
    private String summary;
    @Column(columnDefinition = "TEXT")
    private String description;
    @Column(name = "architecture_description", columnDefinition = "TEXT")
    private String architectureDescription;
    @Column(name = "execution_instructions", columnDefinition = "TEXT")
    private String executionInstructions;
    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_status", nullable = false, length = 20)
    private ProjectLifecycleStatus lifecycleStatus;
    @Column(name = "started_on")
    private LocalDate startedOn;
    @Column(name = "completed_on")
    private LocalDate completedOn;
    @Enumerated(EnumType.STRING)
    @Column(name = "visibility_status", nullable = false, length = 20)
    private ProjectVisibility visibility;
    @Column(name = "is_blocked", nullable = false)
    private boolean blocked;
    @Column(name = "blocked_at")
    private Instant blockedAt;
    @Column(name = "blocked_by_admin_id")
    private Long blockedByAdminId;
    @Column(name = "published_at")
    private Instant publishedAt;
    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Project() { }

    public Project(Member owner, String name, String summary, String description, String architectureDescription,
                   String executionInstructions, ProjectLifecycleStatus lifecycleStatus, LocalDate startedOn,
                   LocalDate completedOn, ProjectVisibility visibility) {
        if (visibility == ProjectVisibility.DELETED) throw new IllegalArgumentException("Project cannot be created as deleted");
        this.owner = owner;
        updateContent(name, summary, description, architectureDescription, executionInstructions, lifecycleStatus,
                startedOn, completedOn);
        this.visibility = visibility;
        if (visibility == ProjectVisibility.PUBLIC) publishedAt = Instant.now();
    }

    public void updateContent(String name, String summary, String description, String architectureDescription,
                              String executionInstructions, ProjectLifecycleStatus lifecycleStatus,
                              LocalDate startedOn, LocalDate completedOn) {
        ensureNotDeleted();
        this.name = name;
        this.summary = summary;
        this.description = description;
        this.architectureDescription = architectureDescription;
        this.executionInstructions = executionInstructions;
        this.lifecycleStatus = lifecycleStatus;
        this.startedOn = startedOn;
        this.completedOn = completedOn;
        if (id != null) updatedAt = Instant.now();
    }

    public void changeVisibility(ProjectVisibility visibility) {
        ensureNotDeleted();
        if (visibility == ProjectVisibility.DELETED) throw new IllegalArgumentException("Use delete to delete a project");
        this.visibility = visibility;
        if (visibility == ProjectVisibility.PUBLIC && publishedAt == null) publishedAt = Instant.now();
    }

    public void delete() {
        ensureNotDeleted();
        visibility = ProjectVisibility.DELETED;
        deletedAt = Instant.now();
    }

    public void setBlocked(boolean blocked, long adminId) {
        ensureNotDeleted();
        if (this.blocked == blocked) return;
        this.blocked = blocked;
        this.blockedAt = blocked ? Instant.now() : null;
        this.blockedByAdminId = blocked ? adminId : null;
    }

    public void touchMedia() {
        ensureNotDeleted();
        updatedAt = Instant.now();
    }

    private void ensureNotDeleted() {
        if (visibility == ProjectVisibility.DELETED) throw new IllegalStateException("Deleted project cannot be changed");
    }

    @PrePersist
    void prePersist() {
        Instant now = creationTime();
        if (id == null) id = TsidGenerator.generate();
        if (visibility == ProjectVisibility.PUBLIC && publishedAt == null) publishedAt = now;
    }

    public Long getId() { return id; }
    public Member getOwner() { return owner; }
    public String getName() { return name; }
    public String getSummary() { return summary; }
    public String getDescription() { return description; }
    public String getArchitectureDescription() { return architectureDescription; }
    public String getExecutionInstructions() { return executionInstructions; }
    public ProjectLifecycleStatus getLifecycleStatus() { return lifecycleStatus; }
    public LocalDate getStartedOn() { return startedOn; }
    public LocalDate getCompletedOn() { return completedOn; }
    public ProjectVisibility getVisibility() { return visibility; }
    public boolean isBlocked() { return blocked; }
    public Instant getBlockedAt() { return blockedAt; }
    public Long getBlockedByAdminId() { return blockedByAdminId; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getDeletedAt() { return deletedAt; }
}
