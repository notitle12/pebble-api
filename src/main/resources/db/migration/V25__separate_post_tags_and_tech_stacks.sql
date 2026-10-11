ALTER TABLE post ADD COLUMN tags TEXT[] NOT NULL DEFAULT '{}';
UPDATE post p SET tags = names.tags FROM (
    SELECT post_id, array_agg(name ORDER BY display_order, id) AS tags
    FROM (
        SELECT pt.post_id, t.name, min(t.display_order) AS display_order, min(t.id) AS id
        FROM post_tag pt JOIN tag t ON t.id = pt.tag_id
        GROUP BY pt.post_id, t.name
    ) distinct_names GROUP BY post_id
) names WHERE names.post_id = p.id;
DROP TABLE post_tag;
ALTER TABLE tag RENAME TO tech_stack;
ALTER TABLE project_tag RENAME TO project_tech_stack;
ALTER TABLE project_tech_stack RENAME COLUMN tag_id TO tech_stack_id;
