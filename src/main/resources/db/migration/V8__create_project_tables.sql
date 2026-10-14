CREATE TABLE project (
    id BIGINT PRIMARY KEY,
    owner_member_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    summary TEXT,
    description TEXT,
    architecture_description TEXT,
    execution_instructions TEXT,
    lifecycle_status VARCHAR(20) NOT NULL,
    started_on DATE,
    completed_on DATE,
    visibility_status VARCHAR(20) NOT NULL,
    is_blocked BOOLEAN NOT NULL DEFAULT FALSE,
    blocked_at TIMESTAMPTZ,
    blocked_by_admin_id BIGINT,
    published_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_project_id_owner UNIQUE (id, owner_member_id),
    CONSTRAINT fk_project_owner FOREIGN KEY (owner_member_id) REFERENCES member (id) ON DELETE RESTRICT,
    CONSTRAINT ck_project_summary_length CHECK (summary IS NULL OR char_length(summary) <= 500),
    CONSTRAINT ck_project_description_length CHECK (description IS NULL OR char_length(description) <= 20000),
    CONSTRAINT ck_project_architecture_length CHECK (architecture_description IS NULL OR char_length(architecture_description) <= 20000),
    CONSTRAINT ck_project_execution_length CHECK (execution_instructions IS NULL OR char_length(execution_instructions) <= 10000),
    CONSTRAINT ck_project_lifecycle CHECK (lifecycle_status IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT ck_project_visibility CHECK (visibility_status IN ('PUBLIC', 'HIDDEN', 'DELETED')),
    CONSTRAINT ck_project_blocked_metadata CHECK (
        (is_blocked = TRUE AND blocked_at IS NOT NULL AND blocked_by_admin_id IS NOT NULL)
        OR (is_blocked = FALSE AND blocked_at IS NULL AND blocked_by_admin_id IS NULL)
    )
);
CREATE INDEX idx_project_public_published ON project (published_at DESC, id DESC)
    WHERE visibility_status = 'PUBLIC' AND is_blocked = FALSE;
CREATE INDEX idx_project_owner_created ON project (owner_member_id, created_at DESC, id DESC);

CREATE TABLE project_feature (
    id BIGINT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    description TEXT NOT NULL,
    display_order INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_project_feature_project FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE,
    CONSTRAINT ck_project_feature_description_length CHECK (char_length(description) <= 2000),
    CONSTRAINT ck_project_feature_order CHECK (display_order >= 0),
    CONSTRAINT uk_project_feature_order UNIQUE (project_id, display_order)
);

CREATE TABLE project_tag (
    project_id BIGINT NOT NULL,
    tag_id BIGINT NOT NULL,
    display_order INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_project_tag PRIMARY KEY (project_id, tag_id),
    CONSTRAINT fk_project_tag_project FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE,
    CONSTRAINT fk_project_tag_tag FOREIGN KEY (tag_id) REFERENCES tag (id) ON DELETE RESTRICT,
    CONSTRAINT ck_project_tag_order CHECK (display_order >= 0),
    CONSTRAINT uk_project_tag_order UNIQUE (project_id, display_order)
);
CREATE INDEX idx_project_tag_tag_project ON project_tag (tag_id, project_id);

CREATE TABLE project_link (
    id BIGINT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    link_type VARCHAR(20) NOT NULL,
    label VARCHAR(100),
    url VARCHAR(2048) NOT NULL,
    display_order INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_project_link_project FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE,
    CONSTRAINT ck_project_link_type CHECK (link_type IN ('GITHUB', 'DEPLOYMENT', 'DOWNLOAD', 'OTHER')),
    CONSTRAINT ck_project_link_order CHECK (display_order >= 0)
);
