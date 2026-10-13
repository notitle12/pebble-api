ALTER TABLE post ADD COLUMN board_display_order INTEGER NOT NULL DEFAULT 0 CHECK (board_display_order >= 0);
WITH ranked AS (
 SELECT id, (row_number() OVER (PARTITION BY author_member_id, board_id ORDER BY display_order, id)-1)::integer AS position
 FROM post WHERE board_id IS NOT NULL AND visibility_status <> 'DELETED'
)
UPDATE post SET board_display_order=ranked.position FROM ranked WHERE post.id=ranked.id;
CREATE INDEX ix_post_board_order ON post(author_member_id,board_id,board_display_order,id) WHERE visibility_status <> 'DELETED';
