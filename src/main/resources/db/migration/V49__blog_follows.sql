CREATE TABLE blog_follow (
    follower_id BIGINT NOT NULL REFERENCES member(id) ON DELETE CASCADE,
    followed_id BIGINT NOT NULL REFERENCES member(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (follower_id, followed_id),
    CONSTRAINT ck_blog_follow_distinct_members CHECK (follower_id <> followed_id)
);

CREATE INDEX ix_blog_follow_followed ON blog_follow (followed_id, created_at DESC, follower_id DESC);
CREATE INDEX ix_blog_follow_follower ON blog_follow (follower_id, created_at DESC, followed_id DESC);
