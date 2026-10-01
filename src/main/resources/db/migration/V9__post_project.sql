ALTER TABLE post ADD COLUMN project_id BIGINT;
ALTER TABLE post ADD CONSTRAINT fk_post_project_owner
    FOREIGN KEY (project_id, author_member_id) REFERENCES project (id, owner_member_id) ON DELETE RESTRICT;
CREATE INDEX idx_post_project_public ON post (project_id, published_at DESC, id DESC)
    WHERE visibility_status = 'PUBLIC' AND is_blocked = FALSE;
CREATE INDEX idx_post_project_owner ON post (project_id, author_member_id);
