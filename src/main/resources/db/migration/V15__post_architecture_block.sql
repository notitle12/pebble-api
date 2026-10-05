ALTER TABLE post_block DROP CONSTRAINT ck_post_block_type;
ALTER TABLE post_block ADD CONSTRAINT ck_post_block_type CHECK (block_type IN ('TEXT', 'CODE', 'TABLE', 'ARCHITECTURE'));
ALTER TABLE post_block ADD CONSTRAINT ck_post_block_architecture_language CHECK (block_type <> 'ARCHITECTURE' OR language IS NULL);
