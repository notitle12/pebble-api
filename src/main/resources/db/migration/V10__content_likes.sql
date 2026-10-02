CREATE TABLE post_like (
    id BIGINT PRIMARY KEY,
    post_id BIGINT NOT NULL REFERENCES post(id) ON DELETE CASCADE,
    member_id BIGINT NOT NULL REFERENCES member(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT ck_post_like_deleted_at CHECK (deleted_at IS NULL OR deleted_at >= created_at)
);
CREATE UNIQUE INDEX uk_post_like_active ON post_like(post_id, member_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_post_like_member ON post_like(member_id);

CREATE TABLE project_like (
    id BIGINT PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    member_id BIGINT NOT NULL REFERENCES member(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT ck_project_like_deleted_at CHECK (deleted_at IS NULL OR deleted_at >= created_at)
);
CREATE UNIQUE INDEX uk_project_like_active ON project_like(project_id, member_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_project_like_member ON project_like(member_id);
