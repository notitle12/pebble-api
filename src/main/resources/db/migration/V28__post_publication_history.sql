-- 첫 공개 발행은 글당 한 번 기록하며 글이 삭제되어도 회원의 누적 기록은 유지한다.
CREATE TABLE post_publication_history (
 post_id BIGINT PRIMARY KEY,
 member_id BIGINT NOT NULL REFERENCES member(id) ON DELETE CASCADE,
 first_published_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_publication_history_member ON post_publication_history(member_id);
INSERT INTO post_publication_history(post_id,member_id,first_published_at)
 SELECT id,author_member_id,published_at FROM post WHERE published_at IS NOT NULL AND is_draft=false;
