ALTER TABLE post_comment
    ALTER COLUMN author_member_id DROP NOT NULL,
    ADD COLUMN guest_nickname VARCHAR(30),
    ADD COLUMN guest_password_hash VARCHAR(255),
    ADD CONSTRAINT ck_post_comment_author_guest CHECK (
        (author_member_id IS NOT NULL AND guest_nickname IS NULL AND guest_password_hash IS NULL)
        OR (author_member_id IS NULL AND guest_nickname IS NOT NULL AND visibility = 'PUBLIC'
            AND (guest_password_hash IS NOT NULL
                 OR (deleted_at IS NOT NULL AND guest_nickname = '삭제된 방문자')))
    );

ALTER TABLE project_comment
    ALTER COLUMN author_member_id DROP NOT NULL,
    ADD COLUMN guest_nickname VARCHAR(30),
    ADD COLUMN guest_password_hash VARCHAR(255),
    ADD CONSTRAINT ck_project_comment_author_guest CHECK (
        (author_member_id IS NOT NULL AND guest_nickname IS NULL AND guest_password_hash IS NULL)
        OR (author_member_id IS NULL AND guest_nickname IS NOT NULL AND visibility = 'PUBLIC'
            AND (guest_password_hash IS NOT NULL
                 OR (deleted_at IS NOT NULL AND guest_nickname = '삭제된 방문자')))
    );

CREATE TABLE guest_abuse_event (
    id BIGSERIAL PRIMARY KEY,
    target VARCHAR(10) NOT NULL,
    comment_id BIGINT NOT NULL,
    ip_address VARCHAR(45) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_guest_abuse_event_target CHECK (target IN ('POST', 'PROJECT'))
);

CREATE INDEX idx_guest_abuse_event_created_at ON guest_abuse_event(created_at);
