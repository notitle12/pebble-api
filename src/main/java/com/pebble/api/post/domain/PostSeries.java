package com.pebble.api.post.domain;

import com.pebble.api.global.id.TsidGenerator;
import com.pebble.api.global.persistence.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "post_series")
public class PostSeries extends BaseTimeEntity {
    @Id private Long id;
    @Column(name = "owner_member_id", nullable = false, updatable = false) private Long ownerMemberId;
    @Column(nullable = false, length = 100) private String name;
    @Column(nullable = false, length = 1000) private String description;

    protected PostSeries() { }
    public PostSeries(long ownerMemberId, String name, String description) {
        this.ownerMemberId = ownerMemberId;
        update(name, description);
    }
    public void update(String name, String description) {
        if (name == null || name.isBlank() || name.codePointCount(0, name.length()) > 100
                || description == null || description.codePointCount(0, description.length()) > 1000) {
            throw new IllegalArgumentException("Invalid series");
        }
        this.name = name;
        this.description = description;
    }
    @PrePersist void prePersist() {
        if (id == null) id = TsidGenerator.generate();
        updatedAt = createdAt;
    }
    public Long getId() { return id; }
    public Long getOwnerMemberId() { return ownerMemberId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
}
