CREATE TABLE post_series (
    id BIGINT PRIMARY KEY,
    owner_member_id BIGINT NOT NULL REFERENCES member(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL CHECK (char_length(name) BETWEEN 1 AND 100 AND btrim(name) <> ''),
    description VARCHAR(1000) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (id, owner_member_id)
);
CREATE INDEX idx_post_series_owner ON post_series(owner_member_id, created_at DESC, id DESC);
ALTER TABLE post ADD COLUMN series_id BIGINT;
ALTER TABLE post ADD COLUMN series_display_order INTEGER NOT NULL DEFAULT 0 CHECK (series_display_order >= 0);
ALTER TABLE post ADD CONSTRAINT fk_post_series_owner
    FOREIGN KEY (series_id, author_member_id) REFERENCES post_series(id, owner_member_id) ON DELETE RESTRICT;
CREATE INDEX idx_post_series_order ON post(series_id, series_display_order, id) WHERE visibility_status <> 'DELETED';
