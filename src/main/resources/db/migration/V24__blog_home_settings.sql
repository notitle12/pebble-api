CREATE TABLE blog_home_settings (
 member_id BIGINT PRIMARY KEY REFERENCES member(id) ON DELETE CASCADE,
 settings JSONB NOT NULL CHECK (jsonb_typeof(settings) = 'object'),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_post_author_public_activity ON post(author_member_id, published_at)
 WHERE visibility_status='PUBLIC' AND is_blocked=false AND is_draft=false;
