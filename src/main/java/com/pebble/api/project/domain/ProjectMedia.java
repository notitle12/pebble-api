package com.pebble.api.project.domain;

import com.pebble.api.global.id.TsidGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "project_media")
public class ProjectMedia {
    @Id
    private Long id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private long projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_role", nullable = false, length = 20, updatable = false)
    private MediaRole mediaRole;

    @Column(name = "storage_key", nullable = false, length = 512, updatable = false)
    private String storageKey;

    @Column(name = "thumbnail_storage_key", nullable = false, length = 512, updatable = false)
    private String thumbnailStorageKey;

    @Column(name = "alt_text", length = 300)
    private String altText;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ProjectMedia() { }

    public ProjectMedia(
            long projectId,
            MediaRole mediaRole,
            String storageKey,
            String thumbnailStorageKey,
            String altText,
            int displayOrder) {
        this.id = TsidGenerator.generate();
        this.projectId = projectId;
        this.mediaRole = mediaRole;
        this.storageKey = storageKey;
        this.thumbnailStorageKey = thumbnailStorageKey;
        update(altText, displayOrder);
        this.createdAt = Instant.now();
    }

    public void update(String altText, int displayOrder) {
        this.altText = altText;
        this.displayOrder = displayOrder;
    }

    public Long getId() {
        return id;
    }

    public long getProjectId() {
        return projectId;
    }

    public MediaRole getMediaRole() {
        return mediaRole;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getThumbnailStorageKey() {
        return thumbnailStorageKey;
    }

    public String getAltText() {
        return altText;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
