-- 회원별 방문이 없어도 날짜 기준 정리 작업을 수행한다.
CREATE INDEX idx_blog_visit_daily_expiry ON blog_visit_daily(visit_date);
