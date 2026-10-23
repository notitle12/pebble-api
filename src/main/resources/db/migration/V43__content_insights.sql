CREATE TABLE content_view_started (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO content_view_started(singleton) VALUES(TRUE);

CREATE TABLE content_view_stats (
    kind VARCHAR(7) NOT NULL CHECK (kind IN ('POST','PROJECT')),
    target_id BIGINT NOT NULL,
    post_id BIGINT REFERENCES post(id) ON DELETE CASCADE,
    project_id BIGINT REFERENCES project(id) ON DELETE CASCADE,
    owner_member_id BIGINT NOT NULL REFERENCES member(id) ON DELETE CASCADE,
    views BIGINT NOT NULL DEFAULT 0 CHECK (views >= 0),
    PRIMARY KEY(kind,target_id),
    CHECK ((kind='POST' AND post_id=target_id AND post_id IS NOT NULL AND project_id IS NULL)
        OR (kind='PROJECT' AND project_id=target_id AND project_id IS NOT NULL AND post_id IS NULL))
);
CREATE INDEX idx_content_view_owner ON content_view_stats(owner_member_id,kind);
CREATE TABLE content_view_day (
    kind VARCHAR(7) NOT NULL,
    target_id BIGINT NOT NULL,
    view_date DATE NOT NULL,
    views BIGINT NOT NULL CHECK (views > 0),
    PRIMARY KEY(kind,target_id,view_date),
    FOREIGN KEY(kind,target_id) REFERENCES content_view_stats(kind,target_id) ON DELETE CASCADE
);
CREATE INDEX idx_content_view_day_date ON content_view_day(view_date);
CREATE TABLE content_view_visitor (
    kind VARCHAR(7) NOT NULL,
    target_id BIGINT NOT NULL,
    view_date DATE NOT NULL,
    visitor_hash CHAR(64) NOT NULL,
    PRIMARY KEY(kind,target_id,view_date,visitor_hash),
    FOREIGN KEY(kind,target_id) REFERENCES content_view_stats(kind,target_id) ON DELETE CASCADE
);
CREATE INDEX idx_content_view_visitor_date ON content_view_visitor(view_date);

CREATE FUNCTION remove_deleted_content_views() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.deleted_at IS NOT NULL OR NEW.visibility_status='DELETED' THEN
        DELETE FROM content_view_stats WHERE kind=TG_ARGV[0] AND target_id=NEW.id;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER deleted_post_views AFTER UPDATE OF deleted_at,visibility_status ON post
    FOR EACH ROW EXECUTE FUNCTION remove_deleted_content_views('POST');
CREATE TRIGGER deleted_project_views AFTER UPDATE OF deleted_at,visibility_status ON project
    FOR EACH ROW EXECUTE FUNCTION remove_deleted_content_views('PROJECT');
