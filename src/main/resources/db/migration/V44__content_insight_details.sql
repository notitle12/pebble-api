-- 유입 출처는 7일 후 삭제한다. 조회 숫자는 일별 90일→월별 24개월→연도별로 합친다.
CREATE TABLE content_view_detail_started (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    source_started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    monthly_coverage_from DATE NOT NULL
);
INSERT INTO content_view_detail_started(singleton,monthly_coverage_from)
SELECT TRUE,greatest((started_at AT TIME ZONE 'Asia/Seoul')::date,(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')::date-89)
FROM content_view_started;

CREATE TABLE content_view_source_day (
    kind VARCHAR(7) NOT NULL,
    target_id BIGINT NOT NULL,
    view_date DATE NOT NULL,
    source VARCHAR(8) NOT NULL CHECK(source IN ('UNKNOWN','DIRECT','INTERNAL','GOOGLE','NAVER','BING','DAUM','GITHUB','KAKAO','OTHER')),
    views BIGINT NOT NULL CHECK(views>0),
    PRIMARY KEY(kind,target_id,view_date,source),
    FOREIGN KEY(kind,target_id) REFERENCES content_view_stats(kind,target_id) ON DELETE CASCADE
);
CREATE INDEX idx_content_view_source_day_date ON content_view_source_day(view_date);
CREATE TABLE content_view_month (
    kind VARCHAR(7) NOT NULL,
    target_id BIGINT NOT NULL,
    view_month DATE NOT NULL CHECK(extract(day from view_month)=1),
    views BIGINT NOT NULL CHECK(views>0),
    PRIMARY KEY(kind,target_id,view_month),
    FOREIGN KEY(kind,target_id) REFERENCES content_view_stats(kind,target_id) ON DELETE CASCADE
);
CREATE INDEX idx_content_view_month_date ON content_view_month(view_month);
CREATE TABLE content_view_year (
    kind VARCHAR(7) NOT NULL,
    target_id BIGINT NOT NULL,
    view_year INTEGER NOT NULL CHECK(view_year BETWEEN 2000 AND 9999),
    views BIGINT NOT NULL CHECK(views>0),
    PRIMARY KEY(kind,target_id,view_year),
    FOREIGN KEY(kind,target_id) REFERENCES content_view_stats(kind,target_id) ON DELETE CASCADE
);
-- 남아 있는 날짜별 숫자만 이전한다. 과거 출처를 추측하지 않는다.
INSERT INTO content_view_source_day SELECT kind,target_id,view_date,'UNKNOWN',views FROM content_view_day
WHERE view_date >= (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')::date-6;
INSERT INTO content_view_month
SELECT kind,target_id,date_trunc('month',view_date)::date,sum(views)
FROM content_view_day GROUP BY kind,target_id,date_trunc('month',view_date)::date;
