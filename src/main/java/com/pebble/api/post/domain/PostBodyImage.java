package com.pebble.api.post.domain;

import com.pebble.api.global.persistence.BaseCreatedEntity;
import com.pebble.api.global.id.TsidGenerator;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name="post_body_image")
public class PostBodyImage extends BaseCreatedEntity {
    @Id private Long id;
    @ManyToOne(fetch=FetchType.LAZY, optional=false)
    @JoinColumn(name="post_id", nullable=false, updatable=false) private Post post;
    @Column(name="storage_key", nullable=false, length=512, updatable=false) private String storageKey;
    protected PostBodyImage() { }
    public PostBodyImage(Post post, String storageKey) {
        this.id=TsidGenerator.generate(); this.post=post; this.storageKey=storageKey; this.createdAt=Instant.now();
    }
    public Long getId() { return id; }
    public Post getPost() { return post; }
    public String getStorageKey() { return storageKey; }
}
