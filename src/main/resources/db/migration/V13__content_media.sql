ALTER TABLE post ADD COLUMN thumbnail_storage_key VARCHAR(512);
CREATE UNIQUE INDEX uk_post_thumbnail_storage_key ON post(thumbnail_storage_key) WHERE thumbnail_storage_key IS NOT NULL;

CREATE TABLE project_media (
    id BIGINT PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    media_role VARCHAR(20) NOT NULL CHECK (media_role IN ('THUMBNAIL', 'SCREENSHOT')),
    storage_key VARCHAR(512) NOT NULL UNIQUE,
    thumbnail_storage_key VARCHAR(512) NOT NULL UNIQUE,
    alt_text VARCHAR(300),
    display_order INTEGER NOT NULL CHECK (display_order >= 0),
    created_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX uk_project_media_thumbnail ON project_media(project_id) WHERE media_role='THUMBNAIL';
CREATE INDEX idx_project_media_order ON project_media(project_id, display_order, id);

-- 부모 삭제·회원 파기로 FK CASCADE가 실행되어도 외부 파일의 삭제 요청은 보존한다.
CREATE TABLE media_deletion_job (
    storage_key VARCHAR(512) PRIMARY KEY,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
    created_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp
);
CREATE INDEX idx_media_deletion_due ON media_deletion_job(next_attempt_at, storage_key);

CREATE FUNCTION enqueue_removed_media() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME='post' THEN
        IF OLD.thumbnail_storage_key IS NOT NULL AND
           (TG_OP='DELETE' OR OLD.thumbnail_storage_key IS DISTINCT FROM NEW.thumbnail_storage_key) THEN
            INSERT INTO media_deletion_job(storage_key) VALUES (OLD.thumbnail_storage_key) ON CONFLICT DO NOTHING;
        END IF;
    ELSE
        INSERT INTO media_deletion_job(storage_key) VALUES (OLD.storage_key), (OLD.thumbnail_storage_key) ON CONFLICT DO NOTHING;
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER post_removed_media AFTER DELETE OR UPDATE OF thumbnail_storage_key ON post
    FOR EACH ROW EXECUTE FUNCTION enqueue_removed_media();
CREATE TRIGGER project_removed_media AFTER DELETE ON project_media
    FOR EACH ROW EXECUTE FUNCTION enqueue_removed_media();
