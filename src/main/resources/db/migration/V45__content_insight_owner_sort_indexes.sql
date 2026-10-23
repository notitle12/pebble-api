-- 조회수 갱신 경로에는 인덱스를 추가하지 않는다. 본인 콘텐츠의 날짜순 탐색만 보조한다.
CREATE INDEX idx_post_insight_owner_created ON post(author_member_id,created_at DESC,id DESC)
    WHERE deleted_at IS NULL AND visibility_status<>'DELETED';
CREATE INDEX idx_project_insight_owner_created ON project(owner_member_id,created_at DESC,id DESC)
    WHERE deleted_at IS NULL AND visibility_status<>'DELETED';
