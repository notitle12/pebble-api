ALTER TABLE post_block DROP CONSTRAINT ck_post_block_type;
ALTER TABLE post_block ADD CONSTRAINT ck_post_block_type CHECK (block_type IN ('TEXT', 'CODE', 'TABLE', 'ARCHITECTURE', 'HTML', 'MARKDOWN', 'DB_SCHEMA'));
ALTER TABLE post_block ADD CONSTRAINT ck_post_block_schema_language CHECK (block_type <> 'DB_SCHEMA' OR language IS NULL);
