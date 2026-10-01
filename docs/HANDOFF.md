# 다음 작업 인계

- 기준 브랜치: dev. AGENTS.md와 작업에 필요한 문서 절만 확인한다.
- 이슈 #31: 공개 Post 기본 문자열 검색을 `feature/31-post-search`에서 구현했다. GET `/api/v1/posts/search`는 필수 q(앞뒤 공백 제거, 1~200 유니코드 코드 포인트), categoryId·tagId·authorId 및 기존 공개 목록의 page·size·sort를 지원한다. 제목·본문 블록 content/title·Category와 상위 Category 이름·Tag 이름을 대소문자 구분 없이 부분 문자열로 찾고 %, _, 역슬래시는 문자 그대로 처리한다. 기존 공개 판정과 EXISTS 기반 본문·태그 검색으로 노출·중복·페이지 집계 오류를 막는다. 별도 dependency·migration은 추가하지 않았다. 임시 PostgreSQL·Redis의 독립 test 프로필에서 `./gradlew clean test bootJar` 191개 테스트와 배포 JAR 빌드 통과, 실패·오류·건너뜀 0개다. Board 변경이 포함되지 않은 dev 기준 검증이며 dev 병합 전이다. CI·실제 브라우저 검증은 별도로 확인한다. 실제 .env·개인 YAML·기존 로그인 DB는 읽거나 변경하지 않았다.
- 검토 대기: Board #29는 별도 브랜치 `feature/29-board-post-placement`의 Draft PR #30이며 CI가 성공했다. 아직 dev 병합 전이다. 두 PR이 PostService와 문서의 같은 부분을 변경하므로 먼저 병합된 변경을 다른 브랜치에 반영하면서 계약을 유지하고 충돌을 조정해야 한다.
- 완료 이슈 #26: Naver 별명 기반 기본 닉네임 접미사, 회원 최초 블로그명·고정 handle 설정, 표시 이름별 7일 쿨타임 및 `profileCompleted` 응답을 구현했다. Member V4는 PR #27로 병합되었고 137개 테스트 통과를 확인했다. 블로그명·닉네임·handle은 각각 고유하며 Naver 식별자는 공개 주소에 쓰지 않는다.
- 이슈 #25: Post CRUD·공개/본인 목록·분류 연결·본문 교체·작성자별 순서 이동을 구현했다. Post 쓰기는 ACTIVE이며 프로필 설정을 마친 USER만 가능하다. 공개 주소는 `/api/v1/blogs/{handle}/posts/{postKey}`이며 postKey는 작성자별 번호 또는 slug다. 번호는 1부터 증가하고 논리 삭제 뒤 재사용하지 않는다. slug는 작성자별로 고유하며 중복 시 `-2`, `-3`을 붙이고 논리 삭제 뒤에도 예약한다. 본인 블로그 기본 정렬은 displayOrder 오름차순, 전체 공개 목록은 publishedAt 내림차순이다. 저장 migration은 V5 기본 Post 테이블, V6 slug·order·post_number다. `./gradlew clean test bootJar` 로컬 테스트 182개와 배포 JAR 빌드가 통과했고 실패·오류·건너뜀은 0개다. 상위 Category 필터의 하위 포함과 긴 slug 중복 접미사의 하이픈도 검증했다. CI·dev 통합 결과는 이슈에 연결된 PR 기록을 확인한다. 검색은 #31 항목을 따르며 Board는 #29 PR 검토 대기다. Project·미디어·좋아요·관리자 Post 운영은 후속 범위다. 실제 .env·개인 YAML·기존 로그인 DB는 읽거나 변경하지 않는다.
- 이슈 #23: Category·Tag 모델·Flyway 저장 기반과 Guest GET `/api/v1/categories`·`/api/v1/tags`를 구현했다. Category는 주제·최대 2단계, 언어·프레임워크는 Tag다. 상위 주제 5개·기술 Tag 8개를 초기 등록하며 활성 하위의 비활성 상위는 INACTIVE 그룹으로 보존한다. Post는 최하위 Category와 Tag를 참조하고, 사용 중인 비활성 분류는 공개 탐색에서 계속 확인할 수 있다. 관리자 편집과 Project 참조는 후속 범위다.
- 이슈 #21: `GET /api/v1/members/me`로 JWT 주체의 본인 계정 정보를 조회한다. member Application이 DB 존재 여부와 ACTIVE 상태를 확인하고, 허용 Origin의 GET·Authorization preflight를 지원한다. 후속 프로필 설정은 #26, 분류는 #23, Post는 #25 항목의 현재 상태를 따른다.
- 완료: 회원·Naver 식별자 저장, Naver 로그인, RS256 Access JWT·Redis 초기 Refresh 세션 발급.
- 이슈 #15: 가이드라인 복원, 공통 오류 규격, JWT 필수 claim 검증, DTO·생성자 정비.
- 이슈 #17: 일반 회원 Refresh 회전·재사용 탐지·로그아웃, 필수 Origin 방어, pepper 버전·기존 세션 호환 구현. 단일 Redis Lua로 처리하며 동시 요청·응답 유실 재시도는 Family 폐기 후 재로그인한다.
- 관련 계약: SECURITY.md 7~9절과 API.md의 Refresh·로그아웃 endpoint. Cookie 요청 CSRF·Origin 방어와 CORS를 함께 다룬다.
- 출시 전 남음: JWT 키 교체, pepper 운영 교체·사고 대응 절차, 로그인·Refresh 속도 제한과 보안 감사 체계, 관리자 인증 및 회원 상태 변경 시 전체 Family 폐기 조정.
- 이슈 #19: RS256을 유지하고 `JWT_PRIVATE_KEY_BASE64`(개인 PKCS#8 DER)와 `JWT_PUBLIC_KEY_BASE64`(공개 X.509 DER) 한 줄 Base64 환경변수만 사용한다. PEM 경로 입력은 지원하지 않는다. `application.yaml`은 `local`/`prod` 프로필 선택용이며, 실제 설정은 `application-local.yaml`(Git 제외, 공유 예시 `application-local.yaml.example`)과 공유 가능한 `application-prod.yaml`의 환경변수 참조에 둔다. 테스트는 `src/test/resources/application-test.yaml`의 독립 `test` 프로필을 사용한다. `bootJar`는 개인 local 설정과 예시를 배포 JAR에서 제외하며, CI는 전체 테스트와 배포 JAR 빌드를 확인한다.
- 실제 브라우저 검증: 사용자가 임시 페이지에서 Naver 로그인과 로그아웃 후 Refresh 401을 확인했다고 보고했다. Agent가 직접 브라우저에서 검증한 결과와 구분한다. 임시 테스트 프런트엔드는 저장소 밖에 있으며 커밋 대상이 아니다.
- Naver callback 경로는 프런트엔드가 `/oauth/callback/naver`, `/auth/naver/callback` 등으로 정한다. 선택한 경로와 `NAVER_REDIRECT_URI`, Naver Developers 등록 URL을 모두 일치시킨다.
- 비밀 값은 환경변수로 제공한다. IntelliJ는 `.env`를 자동으로 읽지 않으므로 Run Configuration에 명시적으로 연결하거나 값을 입력한다. `DB_USERNAME`/`DB_PASSWORD`는 Compose의 `POSTGRES_USER`/`POSTGRES_PASSWORD`와 맞춰야 한다. 초기화된 PostgreSQL 볼륨에는 변경한 `POSTGRES_*` 값이 자동 반영되지 않는다. 키·pepper·실제 자격 증명을 커밋하지 않는다.
- 작업 범위와 Git 상태를 확인하고 dev에서 작업 브랜치를 분기한다. 필요한 테스트와 CI를 확인해 dev에 통합한다.
