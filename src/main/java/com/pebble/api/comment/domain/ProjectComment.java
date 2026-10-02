package com.pebble.api.comment.domain;

import com.pebble.api.member.domain.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "project_comment")
public class ProjectComment extends Comment {
    @Column(name = "project_id", nullable = false, updatable = false)
    private long contentId;

    protected ProjectComment() { }
    public ProjectComment(long contentId, Member author, String body, CommentVisibility visibility) {
        super(author, body, visibility);
        this.contentId = contentId;
    }
    @Override
    public long getContentId() { return contentId; }
}

