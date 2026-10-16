ALTER TABLE post_comment DROP CONSTRAINT ck_post_comment_author_guest;
ALTER TABLE post_comment ADD CONSTRAINT ck_post_comment_author_guest CHECK (
    (author_member_id IS NOT NULL AND guest_nickname IS NULL AND guest_password_hash IS NULL)
    OR (author_member_id IS NULL AND guest_nickname IS NOT NULL
        AND (guest_password_hash IS NOT NULL
             OR (deleted_at IS NOT NULL AND guest_nickname = '삭제된 방문자')))
);

ALTER TABLE project_comment DROP CONSTRAINT ck_project_comment_author_guest;
ALTER TABLE project_comment ADD CONSTRAINT ck_project_comment_author_guest CHECK (
    (author_member_id IS NOT NULL AND guest_nickname IS NULL AND guest_password_hash IS NULL)
    OR (author_member_id IS NULL AND guest_nickname IS NOT NULL
        AND (guest_password_hash IS NOT NULL
             OR (deleted_at IS NOT NULL AND guest_nickname = '삭제된 방문자')))
);
