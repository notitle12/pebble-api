ALTER TABLE post ADD COLUMN project_display_order INTEGER NOT NULL DEFAULT 0;
WITH ranked AS (
 SELECT id, ROW_NUMBER() OVER (PARTITION BY project_id ORDER BY published_at DESC, id DESC) - 1 AS position
 FROM post WHERE project_id IS NOT NULL AND visibility_status <> 'DELETED'
)
UPDATE post p SET project_display_order = ranked.position FROM ranked WHERE ranked.id = p.id;
ALTER TABLE post ADD CONSTRAINT ck_post_project_display_order CHECK (project_display_order >= 0);
CREATE INDEX idx_post_project_display_order ON post(project_id, project_display_order, id) WHERE visibility_status <> 'DELETED';
