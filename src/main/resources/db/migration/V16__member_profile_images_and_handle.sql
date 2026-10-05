ALTER TABLE member DROP CONSTRAINT ck_member_handle;
ALTER TABLE member ADD CONSTRAINT ck_member_handle CHECK (
    handle IS NULL OR (handle ~ '^[a-z][a-z0-9_-]{1,28}[a-z0-9_]$'
        AND handle NOT IN ('admin', 'api', 'auth', 'me', 'posts', 'search', 'settings', 'www'))
);
ALTER TABLE member ADD COLUMN profile_image_storage_key VARCHAR(512);
CREATE UNIQUE INDEX uk_member_profile_image_storage_key ON member(profile_image_storage_key) WHERE profile_image_storage_key IS NOT NULL;

-- 교체·초기화·탈퇴로 제거한 사진은 같은 트랜잭션에서 삭제 큐에 남긴다.
CREATE FUNCTION enqueue_removed_member_image() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.profile_image_storage_key IS NOT NULL AND
       (TG_OP='DELETE' OR OLD.profile_image_storage_key IS DISTINCT FROM NEW.profile_image_storage_key) THEN
        INSERT INTO media_deletion_job(storage_key) VALUES (OLD.profile_image_storage_key) ON CONFLICT DO NOTHING;
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER member_removed_image AFTER DELETE OR UPDATE OF profile_image_storage_key ON member
    FOR EACH ROW EXECUTE FUNCTION enqueue_removed_member_image();
