ALTER TABLE post ADD COLUMN is_draft BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE post ADD CONSTRAINT ck_post_draft_visibility CHECK (NOT is_draft OR visibility_status IN ('HIDDEN', 'DELETED'));
ALTER TABLE post_block DROP CONSTRAINT ck_post_block_type;
ALTER TABLE post_block ADD CONSTRAINT ck_post_block_type CHECK (block_type IN ('TEXT','CODE','TABLE','ARCHITECTURE','HTML','MARKDOWN'));
ALTER TABLE post_block ADD CONSTRAINT ck_post_block_rich_language CHECK (block_type NOT IN ('HTML','MARKDOWN') OR language IS NULL);
CREATE TABLE post_body_image (
 id BIGINT PRIMARY KEY,
 post_id BIGINT NOT NULL REFERENCES post(id) ON DELETE CASCADE,
 storage_key VARCHAR(512) NOT NULL UNIQUE,
 created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_post_body_image_post ON post_body_image(post_id,id);
CREATE FUNCTION enqueue_post_body_image() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO media_deletion_job(storage_key) VALUES (OLD.storage_key) ON CONFLICT DO NOTHING;
 RETURN NULL;
END;
$$;
CREATE TRIGGER post_body_image_removed AFTER DELETE ON post_body_image FOR EACH ROW EXECUTE FUNCTION enqueue_post_body_image();
CREATE FUNCTION remove_deleted_post_images() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.visibility_status='DELETED' THEN DELETE FROM post_body_image WHERE post_id=NEW.id; END IF;
 RETURN NULL;
END;
$$;
CREATE TRIGGER deleted_post_body_images AFTER UPDATE OF visibility_status ON post FOR EACH ROW EXECUTE FUNCTION remove_deleted_post_images();
