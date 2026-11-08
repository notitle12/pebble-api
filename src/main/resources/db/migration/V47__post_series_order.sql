ALTER TABLE post_series ADD COLUMN display_order INTEGER NOT NULL DEFAULT 0;

-- Preserve the existing newest-first list separately for each owner.
WITH ranked AS (
    SELECT id, (row_number() OVER (PARTITION BY owner_member_id ORDER BY created_at DESC, id DESC) - 1)::INTEGER AS position
    FROM post_series
)
UPDATE post_series s SET display_order = ranked.position FROM ranked WHERE s.id = ranked.id;

ALTER TABLE post_series ADD CONSTRAINT ck_post_series_display_order CHECK (display_order >= 0);
CREATE INDEX ix_post_series_owner_order ON post_series (owner_member_id, display_order, id);
