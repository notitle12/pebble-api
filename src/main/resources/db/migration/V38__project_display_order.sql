ALTER TABLE project ADD COLUMN display_order INTEGER NOT NULL DEFAULT 0;
WITH ranked AS (
 SELECT id, ROW_NUMBER() OVER (PARTITION BY owner_member_id ORDER BY created_at DESC, id DESC) - 1 AS position
 FROM project WHERE visibility_status <> 'DELETED'
)
UPDATE project p SET display_order = ranked.position FROM ranked WHERE ranked.id=p.id;
ALTER TABLE project ADD CONSTRAINT ck_project_display_order CHECK (display_order >= 0);
CREATE INDEX idx_project_owner_display_order ON project(owner_member_id, display_order, id) WHERE visibility_status <> 'DELETED';
