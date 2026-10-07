# 보안 설정·OAuth·시간 필드 운영 배포 (2026-10-08)

이슈 #84는 사용자의 민감정보 점검 후 배포 승인에 따라 진행한다. 배포 소스는 PR #79/#82/#83이 병합된 dev 4ea3dc0dc6138472200efb1112e80950aa3d8f20이다. 이 소스의 전체 테스트 498개, bootJar와 CI가 통과했다. 현재 기록 브랜치는 chore/84-security-release다.

## 점검 범위와 결과

- API Git 추적 파일 413개, web Git 추적 파일 172개에서 개인 키 PEM과 대표적인 GitHub/AWS/Google 자격 증명 패턴을 점검하여 발견 0개다. 실제 .env·개인 application-local·SSH 개인 키·운영 Secret 내용은 열거나 출력하지 않았다. 알려진 패턴과 현재 추적 소스 점검이며 모든 과거 유출이나 임의 형식의 비밀을 보장하는 검사는 아니다.
- 깨끗한 dev 작업 폴더에서 bootJar를 빌드했다. JAR의 개인 local 설정·.env·키/PEM·Git 경로는 0개이고, 애플리케이션 클래스/리소스의 대표 자격 증명 패턴도 0개다. 배포 설정은 변수 참조이며 비밀은 기존 런타임 Secret으로 주입한다.
- Docker 컨텍스트 허용 목록은 Dockerfile과 운영 JAR만 허용하고 Dockerfile은 JAR 하나만 복사한다. 서버에는 JAR·Dockerfile·.dockerignore·비밀 없는 백업 스크립트만 전송했다. 기존 api.env 및 private Secret 디렉터리를 변경하지 않았다.
- 프론트 환경변수 참조에서 브라우저 공개 설정은 NEXT_PUBLIC_API_BASE_URL이다. JWT 개인 키·Refresh pepper·OAuth client secret·R2 secret·CDN 서명 키의 클라이언트 전달 경로는 이번 점검에서 발견되지 않았다. 이번 배포는 API만 교체했으며 프론트 빌드를 새로 배포하지 않았다.
- OAuth 공통 grant/profile의 toString은 민감값을 숨기고 공급자 오류 응답 본문을 API 오류로 전달하지 않는다. 네이버 client secret은 서버 간 token 요청에만 사용한다. 실제 운영 authorization 응답 URL의 필드는 client_id/redirect_uri/response_type/state이며 client_secret은 없다. client_id와 OAuth state는 인증 흐름에 필요한 공개 값이다.
- Swagger는 기본 비활성이고 prod에서 허용하지 않는다. 비루트 UID 10001, read-only, cap_drop ALL, no-new-privileges와 API 루프백 포트를 유지한다. DB·Redis에 호스트 공개 포트는 없다.

## 이미지·백업·교체

JAR SHA256: 3d0b0b090fcf2efadc4c67621ce981ac63da413c2e8520b586be1dbc5422af54 (로컬·Oracle 일치).

Oracle ARM64에서 pebble-api:security-4ea3dc0를 빌드했다. 이미지 ID는 sha256:fbb43a7cb7b65099395f62dc05a8ca2f07995d1fcebefba4560d02d3b8e87aeb이고 사용자 10001:10001이다. 이전 정상 이미지는 pebble-api:writer-7870ec6 (sha256:faeddd12def2aee6fca730d78a431d64592391475513fa233d54edc7b9667c27)로 보존한다.

DB 백업은 /opt/pebble/runtime/backups/pebble-20261007T183354Z.dump이다. 백업 스크립트는 custom dump 생성 후 pg_restore --list로 구조를 확인했다. 이번 작업에서 실제 데이터 복원 훈련은 수행하지 않았다.

이전 compose는 /opt/pebble/runtime/backups/compose-before-security-20261007T183521Z.yaml에 보존했다. 운영 compose의 API 이미지 태그만 교체하고 --no-deps로 API 컨테이너를 갱신했다. DB·Redis·방화벽·비밀값은 변경/재시작하지 않았다. V18 success=true를 확인했다. V18은 OAuth 공급자 CHECK 확장이고 시간 공통화 자체는 SQL migration이 없다.

## 운영 검증

- 외부 categories 200, Guest members/me 401, 허용 Origin의 쿠키 없는 refresh 401.
- 네이버 authorization 200, Secure/HttpOnly state 쿠키, 공급자 URL client_secret 없음. 허용하지 않은 Origin 403.
- 운영 루프백 swagger-ui 401, 외부 swagger-ui 404: 운영에서 문서를 공개하지 않는다.
- API readOnly=true, user=10001:10001, capDrop=ALL, 호스트 포트 127.0.0.1:8080만 유지한다. prod 프로필을 확인했다.

실제 사용자 네이버 로그인 완료/토큰 회전 및 R2 이미지 업로드는 이번 비로그인 점검에서 수행하지 않았다. 운영 사용자 계정 흐름의 검증은 이 결과와 구분한다. 카카오·구글 실제 로그인은 아직 활성화하지 않았다. createdBy/updatedBy 변경 주체 감사도 도입하지 않았다.
