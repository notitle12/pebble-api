-- 운영자가 작성한 기존 비회원 댓글의 삭제를 명시적으로 요청했다.
-- 회원 댓글은 유지하고 비회원 식별·비밀번호 컬럼을 제거한다.
DELETE FROM post_comment WHERE author_member_id IS NULL;
DELETE FROM project_comment WHERE author_member_id IS NULL;

ALTER TABLE post_comment
    DROP CONSTRAINT ck_post_comment_author_guest,
    DROP COLUMN guest_nickname,
    DROP COLUMN guest_password_hash,
    ALTER COLUMN author_member_id SET NOT NULL;

ALTER TABLE project_comment
    DROP CONSTRAINT ck_project_comment_author_guest,
    DROP COLUMN guest_nickname,
    DROP COLUMN guest_password_hash,
    ALTER COLUMN author_member_id SET NOT NULL;
