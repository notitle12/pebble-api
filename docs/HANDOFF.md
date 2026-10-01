# 다음 작업 인계

- 기준 브랜치: dev. AGENTS.md와 작업에 필요한 문서 절만 확인한다.
- 이슈 #21: `GET /api/v1/members/me`로 JWT 주체의 본인 계정 정보를 조회한다. member Application이 DB 존재 여부와 ACTIVE 상태를 확인하고, 허용 Origin의 GET·Authorization preflight를 지원한다. 회원 수정·탈퇴와 분류·Post 기능은 후속 범위다.
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
