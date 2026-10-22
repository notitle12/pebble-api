package com.pebble.api.update.domain;

import com.pebble.api.global.id.TsidGenerator;
import com.pebble.api.global.persistence.BaseTimeEntity;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "service_update")
public class ServiceUpdate extends BaseTimeEntity {
    @Id private Long id;
    @Column(nullable = false, length = 160) private String title;
    @Column(nullable = false, length = 300) private String summary;
    @Column(nullable = false, columnDefinition = "text") private String body;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private UpdateType type;
    @Column(length = 40) private String version;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private UpdateStatus status;
    @Column(nullable = false) private long revision;
    @Column(name = "published_at") private Instant publishedAt;
    @Column(name = "deleted_at") private Instant deletedAt;
    protected ServiceUpdate() {}
    public ServiceUpdate(String title, String summary, String body, UpdateType type, String version, UpdateStatus status) {
        this.id = TsidGenerator.generate();
        change(title, summary, body, type, version, status);
        this.revision = 0;
    }
    public void change(String title, String summary, String body, UpdateType type, String version, UpdateStatus status) {
        this.title = title; this.summary = summary; this.body = body; this.type = type; this.version = version;
        // 게시 취소 후 재게시해도 최초 게시 시각과 목록 순서를 보존한다.
        if (status == UpdateStatus.PUBLISHED && publishedAt == null) publishedAt = Instant.now();
        this.status = status; revision++;
    }
    public void delete() { deletedAt = Instant.now(); revision++; }
    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getSummary() { return summary; }
    public String getBody() { return body; }
    public UpdateType getType() { return type; }
    public String getVersion() { return version; }
    public UpdateStatus getStatus() { return status; }
    public long getRevision() { return revision; }
    public Instant getPublishedAt() { return publishedAt; }
}
