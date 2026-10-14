CREATE TABLE post (
    id BIGINT PRIMARY KEY,
    author_member_id BIGINT NOT NULL,
    category_id BIGINT,
    title VARCHAR(200) NOT NULL,
    summary TEXT,
    visibility_status VARCHAR(20) NOT NULL,
    is_blocked BOOLEAN NOT NULL DEFAULT FALSE,
    blocked_at TIMESTAMPTZ,
    blocked_by_admin_id BIGINT,
    published_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_post_author FOREIGN KEY (author_member_id) REFERENCES member (id) ON DELETE RESTRICT,
    CONSTRAINT fk_post_category FOREIGN KEY (category_id) REFERENCES category (id) ON DELETE RESTRICT,
    CONSTRAINT ck_post_summary_length CHECK (summary IS NULL OR char_length(summary) <= 500),
    CONSTRAINT ck_post_visibility CHECK (visibility_status IN ('PUBLIC', 'HIDDEN', 'DELETED')),
    CONSTRAINT ck_post_blocked_metadata CHECK (
        (is_blocked = TRUE AND blocked_at IS NOT NULL AND blocked_by_admin_id IS NOT NULL)
        OR (is_blocked = FALSE AND blocked_at IS NULL AND blocked_by_admin_id IS NULL)
    )
);

CREATE INDEX idx_post_author_created ON post (author_member_id, created_at DESC);
CREATE INDEX idx_post_category_created ON post (category_id, created_at DESC);

CREATE TABLE post_block (
    id BIGINT PRIMARY KEY,
    post_id BIGINT NOT NULL,
    block_type VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    language VARCHAR(50),
    title VARCHAR(100),
    display_order INTEGER NOT NULL,
    CONSTRAINT fk_post_block_post FOREIGN KEY (post_id) REFERENCES post (id) ON DELETE CASCADE,
    CONSTRAINT ck_post_block_type CHECK (block_type IN ('TEXT', 'CODE')),
    CONSTRAINT ck_post_block_content_length CHECK (char_length(content) <= 50000),
    CONSTRAINT ck_post_block_code_language CHECK (block_type <> 'CODE' OR language IS NOT NULL),
    CONSTRAINT ck_post_block_order CHECK (display_order >= 0),
    CONSTRAINT uk_post_block_order UNIQUE (post_id, display_order)
);

CREATE TABLE post_tag (
    post_id BIGINT NOT NULL,
    tag_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_post_tag PRIMARY KEY (post_id, tag_id),
    CONSTRAINT fk_post_tag_post FOREIGN KEY (post_id) REFERENCES post (id) ON DELETE CASCADE,
    CONSTRAINT fk_post_tag_tag FOREIGN KEY (tag_id) REFERENCES tag (id) ON DELETE RESTRICT
);
