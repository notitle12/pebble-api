# 개발용 OpenAPI / Swagger UI

컨트롤러의 요청 DTO, Bean Validation, 성공 응답을 springdoc으로 생성한다. API의 제품·오류·상태 전이 계약은 [API.md](API.md), 인증 정책은 [SECURITY.md](SECURITY.md)가 기준이다. 생성 문서는 이를 대체하지 않는다.

## 실행과 확인

기존 로컬 DB·Redis와 인증 환경변수를 준비한 후 실행한다. 실제 비밀값을 이 문서나 Git에 넣지 않는다.

```sh
./gradlew bootRun --args='--spring.profiles.active=local,api-docs'
```

- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- 회원·공개 API: `http://localhost:8080/v3/api-docs/member`
- 관리자 API: `http://localhost:8080/v3/api-docs/admin`
- 전체 JSON: `http://localhost:8080/v3/api-docs`

포트를 변경했다면 해당 포트로 접속한다. 기본 실행은 문서가 꺼져 있고, `prod`가 포함되면 문서 접근을 거부한다. 운영 도메인에 Swagger를 열지 않는다. `api-docs`를 사용하는 서버도 개발자 PC에서만 실행한다.

## 프론트 협업

UI 우측의 그룹 선택에서 `member`와 `admin`을 구분한다. HTTP 메서드, 경로, 쿼리, 요청 필드와 성공 응답 스키마를 확인한다. `x-pebble-access`는 `PUBLIC`/`USER`/`ADMIN`/`MASTER`이고, ADMIN은 MANAGER 또는 MASTER를 의미한다. 인증이 필요한 작업은 Bearer 스키마로 표시한다.

ID는 JS Number로 변환하지 않고 문자열로 유지한다. 성공은 `data`, 오류는 `error.code/message/details/traceId` 래퍼를 사용한다. 상세 오류 코드·페이지 이동·중복검사·발행 조건은 API.md를 함께 확인한다. 응답별 오류 코드 전부가 자동 생성되는 것은 아니다.

Swagger UI는 스키마 확인용으로 설정하여 실행 버튼과 토큰 저장을 껐다. 실제 연동 테스트는 프론트·통합 테스트에서 수행한다. 네이버 로그인은 authorization → state 쿠키 → callback → login 순서와 허용 Origin이 필요하며, refresh/logout도 Origin을 검사한다. 단순 Bearer 입력만으로 쿠키 기반 인증 흐름을 대체할 수 없다.

새 엔드포인트는 `SecurityEndpoints.API`에 메서드·경로·권한을 등록한다. 같은 목록에서 CORS 허용 메서드와 OpenAPI 인증 표시를 만들며, 미등록 요청은 기본 거부한다. 네이버 인증 CORS도 등록된 정확한 경로만 허용한다. 문서 생성 테스트와 기존 인증/인가 테스트를 통과시킨 뒤 이슈 → 브랜치 → push → PR → CI/검토 → 병합 순서를 따른다.
