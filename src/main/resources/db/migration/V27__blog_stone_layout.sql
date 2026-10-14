CREATE TABLE blog_stone_layout (
 member_id BIGINT PRIMARY KEY REFERENCES member(id) ON DELETE CASCADE,
 version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
 stones JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(stones) = 'array'),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
