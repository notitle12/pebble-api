CREATE TABLE board (
    id BIGINT PRIMARY KEY,
    owner_member_id BIGINT NOT NULL,
    parent_id BIGINT,
    name VARCHAR(50) NOT NULL,
    display_order INTEGER NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_board_id_owner UNIQUE (id, owner_member_id),
    CONSTRAINT fk_board_owner FOREIGN KEY (owner_member_id) REFERENCES member (id) ON DELETE RESTRICT,
    CONSTRAINT fk_board_parent_owner FOREIGN KEY (parent_id, owner_member_id) REFERENCES board (id, owner_member_id) ON DELETE RESTRICT,
    CONSTRAINT ck_board_order CHECK (display_order >= 0),
    CONSTRAINT ck_board_parent CHECK (parent_id IS NULL OR parent_id <> id)
);
CREATE INDEX idx_board_owner_order ON board (owner_member_id, display_order, id) WHERE deleted_at IS NULL;
CREATE INDEX idx_board_parent ON board (parent_id) WHERE deleted_at IS NULL;
ALTER TABLE post ADD COLUMN board_id BIGINT;
ALTER TABLE post ADD CONSTRAINT fk_post_board_author FOREIGN KEY (board_id, author_member_id)
    REFERENCES board (id, owner_member_id) ON DELETE RESTRICT;
CREATE INDEX idx_post_board_order ON post (board_id, display_order, id);
