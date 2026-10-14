ALTER TABLE member ADD COLUMN blog_name VARCHAR(100);
ALTER TABLE member ADD COLUMN handle VARCHAR(30);
ALTER TABLE member ADD COLUMN profile_completed_at TIMESTAMPTZ;
ALTER TABLE member ADD COLUMN nickname_changed_at TIMESTAMPTZ;
ALTER TABLE member ADD COLUMN blog_name_changed_at TIMESTAMPTZ;

-- 기존 이름을 먼저 예약하여 접미사가 실제 다른 회원의 이름을 덮어쓰지 않게 한다.
DO $$
DECLARE duplicate_member RECORD;
        suffix INTEGER;
        candidate VARCHAR(30);
BEGIN
    FOR duplicate_member IN
        SELECT id, nickname FROM (
            SELECT id, nickname, created_at,
                   row_number() OVER (PARTITION BY nickname ORDER BY created_at, id) AS position
            FROM member
        ) duplicates WHERE position > 1 ORDER BY created_at, id
    LOOP
        suffix := 2;
        LOOP
            candidate := left(duplicate_member.nickname, 30 - length(suffix::TEXT) - 1) || '-' || suffix;
            EXIT WHEN NOT EXISTS (SELECT 1 FROM member WHERE nickname = candidate);
            suffix := suffix + 1;
        END LOOP;
        UPDATE member SET nickname = candidate WHERE id = duplicate_member.id;
    END LOOP;
END $$;

UPDATE member SET nickname_changed_at = created_at;
ALTER TABLE member ADD CONSTRAINT uk_member_nickname UNIQUE (nickname);
ALTER TABLE member ADD CONSTRAINT uk_member_blog_name UNIQUE (blog_name);
ALTER TABLE member ADD CONSTRAINT uk_member_handle UNIQUE (handle);
ALTER TABLE member ADD CONSTRAINT ck_member_handle CHECK (
    handle IS NULL OR (handle ~ '^[a-z][a-z0-9-]{1,28}[a-z0-9]$'
        AND handle NOT IN ('admin', 'api', 'auth', 'me', 'posts', 'search', 'settings', 'www'))
);
ALTER TABLE member ADD CONSTRAINT ck_member_profile CHECK (
    (profile_completed_at IS NULL AND blog_name IS NULL AND handle IS NULL AND blog_name_changed_at IS NULL)
    OR (profile_completed_at IS NOT NULL AND blog_name IS NOT NULL AND handle IS NOT NULL
        AND nickname_changed_at IS NOT NULL AND blog_name_changed_at IS NOT NULL)
);

CREATE FUNCTION prevent_member_handle_change() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.handle IS NOT NULL AND NEW.handle IS DISTINCT FROM OLD.handle THEN
        RAISE EXCEPTION '공개 아이디는 변경할 수 없습니다.' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER member_handle_immutable BEFORE UPDATE OF handle ON member
    FOR EACH ROW EXECUTE FUNCTION prevent_member_handle_change();
