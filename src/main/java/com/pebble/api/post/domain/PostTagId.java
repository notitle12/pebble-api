package com.pebble.api.post.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class PostTagId implements Serializable {

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "tag_id", nullable = false)
    private Long tagId;

    protected PostTagId() {
    }

    public PostTagId(Long postId, Long tagId) {
        this.postId = postId;
        this.tagId = tagId;
    }

    public Long getPostId() { return postId; }
    public Long getTagId() { return tagId; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof PostTagId that)) return false;
        return Objects.equals(postId, that.postId) && Objects.equals(tagId, that.tagId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(postId, tagId);
    }
}
