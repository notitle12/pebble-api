CREATE TABLE blog_link (
 id BIGINT PRIMARY KEY, member_id BIGINT NOT NULL REFERENCES member(id) ON DELETE CASCADE,
 kind VARCHAR(10) NOT NULL CHECK (kind IN ('GITHUB','SITE')), label VARCHAR(50) NOT NULL,
 url VARCHAR(2048) NOT NULL, logo_storage_key VARCHAR(512), display_order INTEGER NOT NULL CHECK(display_order BETWEEN 0 AND 5),
 CHECK ((kind='GITHUB' AND logo_storage_key IS NULL) OR (kind='SITE' AND logo_storage_key IS NOT NULL)),
 UNIQUE(member_id,display_order)
);
CREATE UNIQUE INDEX uk_blog_github ON blog_link(member_id) WHERE kind='GITHUB';
CREATE UNIQUE INDEX uk_blog_logo ON blog_link(logo_storage_key) WHERE logo_storage_key IS NOT NULL;
CREATE FUNCTION enqueue_removed_blog_logo() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.logo_storage_key IS NOT NULL AND (TG_OP='DELETE' OR OLD.logo_storage_key IS DISTINCT FROM NEW.logo_storage_key) THEN
  INSERT INTO media_deletion_job(storage_key) VALUES(OLD.logo_storage_key) ON CONFLICT DO NOTHING;
 END IF;
 RETURN NULL;
END; $$;
CREATE TRIGGER blog_removed_logo AFTER DELETE OR UPDATE OF logo_storage_key ON blog_link FOR EACH ROW EXECUTE FUNCTION enqueue_removed_blog_logo();
CREATE TABLE blog_visit_stats (
 member_id BIGINT PRIMARY KEY REFERENCES member(id) ON DELETE CASCADE,
 total_visitors BIGINT NOT NULL CHECK(total_visitors>=0), visit_date DATE NOT NULL,
 today_visitors BIGINT NOT NULL CHECK(today_visitors>=0 AND today_visitors<=total_visitors)
);
CREATE TABLE blog_visit_daily (
 member_id BIGINT NOT NULL REFERENCES member(id) ON DELETE CASCADE,
 visit_date DATE NOT NULL, visitor_hash CHAR(64) NOT NULL,
 PRIMARY KEY(member_id,visit_date,visitor_hash)
);
