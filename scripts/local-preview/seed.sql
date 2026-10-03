-- 운영 마이그레이션이 아닌 로컬 개발 전용 fixture다. OAuth 계정·자격 증명을 생성하지 않는다.
INSERT INTO member (id,nickname,status,blog_name,handle,profile_completed_at,nickname_changed_at,blog_name_changed_at,created_at,updated_at)
VALUES (910000000000001000,'로컬검증','ACTIVE','로컬 연동 검증','local-preview',now(),now(),now(),now(),now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO project (id,owner_member_id,name,summary,description,architecture_description,execution_instructions,lifecycle_status,started_on,completed_on,visibility_status,published_at,created_at,updated_at)
SELECT 910000000000003000+n,910000000000001000,
  CASE n WHEN 1 THEN '[로컬 테스트] Pebble API' WHEN 2 THEN '[로컬 테스트] React 사용자 화면' ELSE '[로컬 테스트] 비공개 프로젝트' END,
  '실제 로컬 API로 프론트 화면을 검증하는 테스트 프로젝트입니다.',
  'PostgreSQL에 저장된 데이터입니다. 목록·검색·상세와 관련 글을 확인하세요.',
  'Next.js SSR → Spring Boot API → PostgreSQL',
  '로컬 API 8080과 프론트 3100을 실행한 뒤 공개 화면을 확인합니다.',
  CASE WHEN n=1 THEN 'COMPLETED' ELSE 'IN_PROGRESS' END,
  DATE '2026-09-01', CASE WHEN n=1 THEN DATE '2026-10-01' ELSE NULL END,
  CASE WHEN n=3 THEN 'HIDDEN' ELSE 'PUBLIC' END,
  CASE WHEN n=3 THEN NULL ELSE now()-n*interval '1 hour' END,now(),now()
FROM generate_series(1,3) n ON CONFLICT (id) DO NOTHING;

INSERT INTO project_feature (id,project_id,title,description,display_order,created_at,updated_at)
VALUES (910000000000004001,910000000000003001,'공개 콘텐츠 탐색','실제 API의 글·프로젝트·태그·분류 조회를 확인합니다.',0,now(),now()) ON CONFLICT (id) DO NOTHING;
INSERT INTO project_link (id,project_id,link_type,label,url,display_order,created_at)
VALUES (910000000000005001,910000000000003001,'GITHUB','Pebble API 저장소','https://github.com/notitle12/pebble-api',0,now()) ON CONFLICT (id) DO NOTHING;
INSERT INTO project_tag (project_id,tag_id,display_order,created_at)
SELECT 910000000000003000+n,t.id,0,now() FROM generate_series(1,2) n JOIN tag t ON t.slug=CASE WHEN n=1 THEN 'spring-boot' ELSE 'react' END
ON CONFLICT (project_id,tag_id) DO NOTHING;

-- 공개 24개(Backend 21/Frontend 3), HIDDEN·DELETED 각각 1개.
INSERT INTO post (id,author_member_id,category_id,title,summary,visibility_status,published_at,deleted_at,created_at,updated_at,slug,post_number,display_order,project_id)
SELECT 910000000000001100+n,910000000000001000,c.id,
  '[로컬 테스트] '||CASE WHEN n<=21 THEN 'Spring Boot 연동 기록 ' WHEN n<=24 THEN 'React 화면 기록 ' ELSE '공개되면 안 되는 글 ' END||lpad(n::text,2,'0'),
  '실제 DB 테스트 데이터입니다. 검색·태그·분류·페이지 이동과 상세 화면을 확인합니다.',
  CASE WHEN n=25 THEN 'HIDDEN' WHEN n=26 THEN 'DELETED' ELSE 'PUBLIC' END,
  CASE WHEN n<=24 THEN now()-n*interval '1 minute' ELSE NULL END,
  CASE WHEN n=26 THEN now() ELSE NULL END,now(),now(),'integration-'||n,n,n-1,
  CASE WHEN n<=21 THEN 910000000000003001 ELSE 910000000000003002 END
FROM generate_series(1,26) n JOIN category c ON c.slug=CASE WHEN n<=21 THEN 'backend' ELSE 'frontend' END
ON CONFLICT (id) DO NOTHING;
INSERT INTO post_tag (post_id,tag_id,created_at)
SELECT p.id,t.id,now() FROM post p JOIN tag t ON t.slug=CASE WHEN p.post_number<=21 THEN 'spring-boot' ELSE 'react' END
WHERE p.author_member_id=910000000000001000 AND p.id BETWEEN 910000000000001101 AND 910000000000001126
ON CONFLICT (post_id,tag_id) DO NOTHING;
INSERT INTO post_block (id,post_id,block_type,content,language,title,display_order)
SELECT 910000000000002000+2*n,910000000000001100+n,'TEXT',
  '이 글은 목 API가 아닌 로컬 PostgreSQL에서 조회한 테스트 글입니다.'||E'\n\n'||'한글 본문과 <script>alert("escape")</script> 문자열을 안전하게 표시해야 합니다.',NULL,NULL,0
FROM generate_series(1,26) n ON CONFLICT (id) DO NOTHING;
INSERT INTO post_block (id,post_id,block_type,content,language,title,display_order)
SELECT 910000000000002001+2*n,910000000000001100+n,'CODE',
  'System.out.println("Pebble local integration");','JAVA','실제 API 코드 블록',1
FROM generate_series(1,26) n ON CONFLICT (id) DO NOTHING;
DO $$ BEGIN
 IF (SELECT count(*) FROM post WHERE author_member_id=910000000000001000 AND id BETWEEN 910000000000001101 AND 910000000000001126) <> 26
 OR (SELECT count(*) FROM project_tag WHERE project_id IN (910000000000003001,910000000000003002)) <> 2 THEN
   RAISE EXCEPTION '초기 Backend/Frontend 분류 및 Spring Boot/React 태그가 필요합니다';
 END IF;
END $$;
SELECT '테스트 데이터 준비 완료' AS result, count(*) AS posts FROM post WHERE author_member_id=910000000000001000;
