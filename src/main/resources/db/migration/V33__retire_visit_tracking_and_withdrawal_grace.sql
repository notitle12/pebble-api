-- 방문자별 식별값은 더 이상 수집하지 않는다. 개인을 식별하지 않는 과거 집계 수치는 보존한다.
DELETE FROM blog_visit_daily;

-- 기존 탈퇴 대기 계정도 유예 없이 기존 파기 작업의 다음 실행 대상으로 전환한다.
UPDATE member SET withdrawal_scheduled_at = LEAST(withdrawal_scheduled_at, CURRENT_TIMESTAMP)
WHERE status = 'WITHDRAWAL_PENDING';
