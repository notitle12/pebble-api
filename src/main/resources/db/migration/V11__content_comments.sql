CREATE TABLE post_comment (
    id BIGINT PRIMARY KEY,
    post_id BIGINT NOT NULL REFERENCES post(id) ON DELETE CASCADE,
    author_member_id BIGINT NOT NULL REFERENCES member(id) ON DELETE CASCADE,
    body TEXT NOT NULL,
    visibility VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT ck_post_comment_body CHECK (
        (deleted_at IS NULL AND char_length(body) BETWEEN 1 AND 2000)
        OR (deleted_at IS NOT NULL AND body = '')
    ),
    CONSTRAINT ck_post_comment_visibility CHECK (visibility IN ('PUBLIC', 'SECRET'))
);
CREATE INDEX idx_post_comment_content_created ON post_comment(post_id, created_at, id) WHERE deleted_at IS NULL;
CREATE INDEX idx_post_comment_author ON post_comment(author_member_id);

CREATE TABLE project_comment (
    id BIGINT PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    author_member_id BIGINT NOT NULL REFERENCES member(id) ON DELETE CASCADE,
    body TEXT NOT NULL,
    visibility VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT ck_project_comment_body CHECK (
        (deleted_at IS NULL AND char_length(body) BETWEEN 1 AND 2000)
        OR (deleted_at IS NOT NULL AND body = '')
    ),
    CONSTRAINT ck_project_comment_visibility CHECK (visibility IN ('PUBLIC', 'SECRET'))
);
CREATE INDEX idx_project_comment_content_created ON project_comment(project_id, created_at, id) WHERE deleted_at IS NULL;
CREATE INDEX idx_project_comment_author ON project_comment(author_member_id);


