ALTER TABLE post_block DROP CONSTRAINT ck_post_block_type;
ALTER TABLE post_block ADD CONSTRAINT ck_post_block_type CHECK (block_type IN ('TEXT', 'CODE', 'TABLE'));
ALTER TABLE post_block ADD CONSTRAINT ck_post_block_table_language CHECK (block_type <> 'TABLE' OR language IS NULL);
