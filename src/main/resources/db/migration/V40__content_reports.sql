CREATE TABLE content_report (
    id BIGINT PRIMARY KEY,
    target_type VARCHAR(20) NOT NULL CHECK (target_type IN ('POST','PROJECT','POST_COMMENT','PROJECT_COMMENT')),
    target_id BIGINT NOT NULL,
    target_title VARCHAR(200) NOT NULL,
    post_id BIGINT REFERENCES post(id) ON DELETE CASCADE,
    project_id BIGINT REFERENCES project(id) ON DELETE CASCADE,
    post_comment_id BIGINT REFERENCES post_comment(id) ON DELETE CASCADE,
    project_comment_id BIGINT REFERENCES project_comment(id) ON DELETE CASCADE,
    reporter_member_id BIGINT REFERENCES member(id) ON DELETE SET NULL,
    reason VARCHAR(20) NOT NULL CHECK (reason IN ('PRIVACY','COPYRIGHT','ABUSE','SPAM','OTHER')),
    detail VARCHAR(1000) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('OPEN','RESOLVED')),
    action VARCHAR(20) CHECK (action IN ('BLOCK','DELETE','DISMISS')),
    handled_by_admin_id BIGINT REFERENCES admin_account(id) ON DELETE SET NULL,
    handled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_content_report_member UNIQUE (target_type,target_id,reporter_member_id),
    CONSTRAINT ck_content_report_target CHECK (
      (target_type='POST' AND post_id IS NOT NULL AND post_id=target_id AND project_id IS NULL AND post_comment_id IS NULL AND project_comment_id IS NULL)
      OR (target_type='PROJECT' AND project_id IS NOT NULL AND project_id=target_id AND post_id IS NULL AND post_comment_id IS NULL AND project_comment_id IS NULL)
      OR (target_type='POST_COMMENT' AND post_id IS NOT NULL AND post_comment_id IS NOT NULL AND post_comment_id=target_id AND project_id IS NULL AND project_comment_id IS NULL)
      OR (target_type='PROJECT_COMMENT' AND project_id IS NOT NULL AND project_comment_id IS NOT NULL AND project_comment_id=target_id AND post_id IS NULL AND post_comment_id IS NULL)),
    CONSTRAINT ck_content_report_resolution CHECK (
      (status='OPEN' AND action IS NULL AND handled_at IS NULL AND handled_by_admin_id IS NULL)
      OR (status='RESOLVED' AND action IS NOT NULL AND handled_at IS NOT NULL)),
    CONSTRAINT ck_content_report_comment_action CHECK (target_type NOT IN ('POST_COMMENT','PROJECT_COMMENT') OR action IS DISTINCT FROM 'BLOCK')
);
CREATE INDEX idx_content_report_created ON content_report(created_at DESC,id DESC);
CREATE INDEX idx_content_report_status_created ON content_report(status,created_at DESC,id DESC);
CREATE INDEX idx_content_report_type_created ON content_report(target_type,created_at DESC,id DESC);
CREATE INDEX idx_content_report_reporter ON content_report(reporter_member_id);
CREATE INDEX idx_content_report_post ON content_report(post_id);
CREATE INDEX idx_content_report_project ON content_report(project_id);
CREATE INDEX idx_content_report_post_comment ON content_report(post_comment_id);
CREATE INDEX idx_content_report_project_comment ON content_report(project_comment_id);
