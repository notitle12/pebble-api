ALTER TABLE post ADD COLUMN slug VARCHAR(200);
ALTER TABLE post ADD COLUMN display_order INTEGER NOT NULL DEFAULT 0;
ALTER TABLE post ADD CONSTRAINT uk_post_author_slug UNIQUE (author_member_id, slug);
ALTER TABLE post ADD COLUMN post_number BIGINT;
UPDATE post p SET post_number = numbered.number
FROM (
    SELECT id, row_number() OVER (PARTITION BY author_member_id ORDER BY created_at, id) AS number FROM post
) numbered WHERE p.id = numbered.id;
ALTER TABLE post ALTER COLUMN post_number SET NOT NULL;
ALTER TABLE post ADD CONSTRAINT ck_post_number CHECK (post_number > 0);
ALTER TABLE post ADD CONSTRAINT uk_post_author_number UNIQUE (author_member_id, post_number);
ALTER TABLE post ADD CONSTRAINT ck_post_slug CHECK (
    slug IS NULL OR (slug <> '' AND slug !~ '^[0-9]+$' AND slug <> 'search')
);
ALTER TABLE post ADD CONSTRAINT ck_post_display_order CHECK (display_order >= 0);

UPDATE post p SET display_order = ordered.position
FROM (
    SELECT id, (row_number() OVER (PARTITION BY author_member_id ORDER BY created_at DESC, id DESC) - 1)::INTEGER AS position
    FROM post WHERE visibility_status <> 'DELETED'
) ordered WHERE p.id = ordered.id;

CREATE INDEX idx_post_author_order ON post (author_member_id, display_order, id);
CREATE INDEX idx_post_public_published ON post (published_at DESC, id DESC)
    WHERE visibility_status = 'PUBLIC' AND is_blocked = FALSE;
CREATE INDEX idx_post_tag_tag ON post_tag (tag_id, post_id);
