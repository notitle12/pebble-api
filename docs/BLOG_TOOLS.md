# 블로그 방문·검색·외부 링크 (Issue #88)

## 방문 통계

GET `/api/v1/blogs/{handle}/visits`는 `{data:{totalVisitors,todayVisitors,date,timeZone}}`를 조회한다. POST 같은 경로는 본문·query 없이 방문을 기록하고 같은 응답을 반환한다. `timeZone=Asia/Seoul`, `date=YYYY-MM-DD`. 통계는 기능 적용 이후부터 시작하며 과거 트래픽을 복원하지 않는다. 전체는 날짜별 중복 제외 방문의 누적 합이다. 한 브라우저가 같은 블로그에 같은 한국 날짜에 여러 번 방문해도 한 번만 증가한다. 쿠키 삭제·다른 브라우저는 별도 방문이고 사람이 아닌 브라우저나 통계 조작을 완벽히 구별하는 수치는 아니다.

API가 익명 UUID v4 `pebble_blog_visitor` 쿠키를 발급한다. host-only, HttpOnly, SameSite=Lax, `/api/v1/blogs` Path, 180일이며 prod에서 Secure다. 회원 인증에 사용하지 않는다. DB에는 블로그·날짜별 SHA-256 해시만 남기고 이전 날짜 중복 키는 요청 시 정리한다. 원시 IP·쿠키·회원 인증 정보는 통계에 저장하지 않는다. 유일 키와 PostgreSQL upsert로 중복·동시 증가를 처리한다.

POST 방문은 정확한 경로의 공개 허용이며 CSRF 토큰 대신 기존 CookieOriginFilter의 단일 allowlisted Origin을 필수 적용한다. 누락·null·복수·악성 Origin은 403이다. 읽기 API에는 이 예외를 적용하지 않는다. 단일 API 인스턴스에서 remoteAddr 해시별 120회/분, 최대 10,000개 1분 버킷으로 쓰기 남용을 제한하며 초과 시 VISIT_RATE_LIMITED 429다. 해시는 일시적 메모리에만 저장된다. X-Forwarded-For를 신뢰하지 않으므로 reverse proxy 뒤에서는 공유 한도로 작동할 수 있다. 다중 인스턴스·대규모 서비스 전에는 신뢰 프록시 경계와 분산 제한을 별도 검토한다.

## 블로그 검색

GET `/api/v1/blogs/{handle}/posts?q=...`는 기존 목록 계약·페이지·분류·태그 조건에 q(200 code point 이내)를 추가한다. 해당 공개 블로그 작성자로 제한하며 공개·차단·탈퇴 정책과 본문/제목/태그/분류 검색을 재사용한다. 중복 q와 잘못된 조건은 400이다. 프론트 검색 제출은 게시판·프로젝트 조건을 초기화한다.

## 외부 링크

GET `/api/v1/blogs/{handle}/links`는 공개 블로그 조회 정책을 따른다. GET/PUT `/api/v1/members/me/blog-links`는 프로필 완료 ACTIVE USER 본인만 사용한다. 응답: `{data:{githubUrl:string|null,sites:[{id:string,label:string,url:string,logoUrl:string|null}]}}`. private storage key는 노출하지 않는다. 저장소 비활성 시 기존 로고 URL은 null일 수 있다.

PUT JSON 또는 multipart를 받는다. JSON `{githubUrl:null|HTTPS github.com 경로,sites:[{id?:기존사이트ID,label:1..50자,url:HTTPS}]}`. 사이트 최대5, URL 최대2048자, credentials·비표준 포트·제어 문자 금지. GitHub 로고는 기본 SVG로 표시한다. sites의 기존 ID는 본인 소유를 확인하며 그 로고를 유지한다. 새 사이트에는 로고 파일이 필수다. multipart 필드 `links` JSON 문자열 + 사이트 인덱스 `logo0`..`logo4`; 파일당2MiB이며 PNG/JPEG/WebP만 실제 디코딩 후 WebP thumbnail로 저장한다. 임의 URL 이미지를 서버에서 다운로드하거나 사용자 입력으로 storage key를 선택하지 않는다. 알 수 없는 필드·중복 ID·과다사이트·다른 회원 ID는 거부한다.

회원 행 잠금 이후 모든 조건을 검증하고 하나의 DB 트랜잭션으로 링크를 교체한다. 업로드는 기존 MediaDeletionQueue의 stage/lock/retain을 사용하여 실패·롤백된 파일을 회수한다. 삭제·교체·탈퇴 cascade의 이전 로고는 트리거로 큐에 남기고 회수 작업은 blog_link 참조도 검사한다. CDN 서명과 Worker 허용 목록에는 `member/<UUID>/blog-logo.webp`만 추가하며 기존 서명/만료/캐시 이전 검증을 유지한다. 프론트 취소는 저장 전 초안을 복원하고 결과 불명확 시 자동 재저장하지 않는다.
