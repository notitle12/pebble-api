ALTER TABLE post_block
    ADD COLUMN alignment VARCHAR(10) NOT NULL DEFAULT 'LEFT',
    ADD CONSTRAINT ck_post_block_alignment CHECK (alignment IN ('LEFT', 'CENTER', 'RIGHT'));
