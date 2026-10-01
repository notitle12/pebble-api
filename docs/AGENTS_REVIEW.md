# AGENTS.md 복원과 기존 구현 점검

검토일: 2026-10-01. 기준 커밋: `620fafb` (Naver 로그인 PR #14 병합).

## 점검 후 조치 — 이슈 #15

- 아래 네 항목을 수정했다. 공통 ErrorCode·GlobalErrorCode·ApplicationException을 도입하고 Feature advice를 제거했다.
- JWT 필수 claim·타입·회원 ID·시간 범위를 검증하고, 외부 DB 없이 새 JWT·MVC 오류 테스트 40개가 통과했다. 최초 실패했던 테스트는 잘못된 토큰을 실제 서명해 검증하도록 보완했다.
- 로그인 응답 DTO를 presentation에 배치하고 단순 주입을 Lombok으로 정리했다. 전체 로그인 회귀는 PR의 PostgreSQL·Redis CI로 확인한다.
- 상시 입력되는 AGENTS.md는 중복 주제를 병합해 4,702바이트로 줄였다. 아래 본문은 최초 점검 기록이며 후속 기능·출시 과제는 계속 남아 있다.

## 범위와 방법

- 누락된 루트 `AGENTS.md`를 사용자 첨부안으로부터 복원하고 반복 설명을 압축했다.
- 주 Agent는 인증·JWT·Redis 초기 세션·회원 동시 가입 경계와 관련 보안 문서를 확인했다.
- `gpt-6-luna` 하위 Agent 하나가 일반 코딩 규칙·DTO·패키지 배치를 읽기 전용으로 점검했다. 전체 대화 이력을 전달하지 않았다.
- 이번 점검은 정적 검토다. 구현 변경이나 테스트 재실행은 하지 않았다. 직전 PR의 로컬 테스트 및 CI 성공은 새로 발견한 실패 경로까지 검증했다는 의미가 아니다.

## 가이드라인 보완

| 첨부안에서 보완한 부분 | 반영 내용 |
|---|---|
| 반복된 모델·탐색 설명 | 22개 주제는 유지하고 중복 문장과 흐름도를 압축 |
| 문서를 최상위 원칙으로 표현 | 사용자 요청과 실행 환경의 상위 지침 우선 명시 |
| 실제로 없는 공통 예외 클래스 이름 | 현재 `ApiExceptionHandler`, `ApiResponse`, `ApiErrorResponse` 사용 |
| 모든 주입 생성자에 Lombok 적용 | 단순 주입은 Lombok, 검증·변환·객체 조립은 명시 생성자 허용 |
| 주 모델을 Sol로 지정 | 환경에서 선택한 주 모델을 존중하고 지원되는 하위 모델로 위임 |
| 성공한 하위 작업은 실패 시에만 검토 | 계약·범위·테스트 결과 확인은 유지하고 보안·트랜잭션 경계는 별도 검토 |
| 위임을 통한 절약 | 작은 작업은 직접 처리, 기본 하위 Agent 하나, 필요한 컨텍스트만 전달 |

초기 압축 시 파일 크기는 첨부안 21,911바이트에서 10,191바이트로 줄었다. 이후 위임의 사용량 비용을 설명하는 문장을 추가했다. 이는 파일의 입력 분량 감소이며 계정 사용량이나 총 토큰의 절감률을 의미하지 않는다.

## 수정할 사항

### 1. JWT 필수 claim 검증 보완 — 우선 처리

- 근거: `src/main/java/com/pebble/api/global/security/JwtConfiguration.java:72`.
- 현재 발급기는 `exp`, `nbf`, `iat`, `jti`를 포함하지만 검증기는 이 필드의 존재를 명시적으로 요구하지 않는다. `sub`도 `member:` 접두사만 확인한다.
- 실제 사용 중인 Spring Security 6.5.11의 소스에서 `JwtTimestampValidator`는 `exp`·`nbf`가 있을 때만 시간 검증을 수행하며, Nimbus 단계의 별도 claim 검증도 비활성화되어 있음을 확인했다.
- 조치: 필수 claim 존재·형식과 회원 ID 형식을 검증한다. 정상 서명된 토큰에서 필수 claim 누락, 빈 주체, 만료·미래 유효 시각, 잘못된 issuer·audience·role·token_type 및 서명을 거부하는 테스트를 보강한다.
- 현재 공개 endpoint 외에는 모두 차단되어 있다. 발급된 정상 토큰의 오류나 현재 보호 API의 인증 우회가 재현된 것은 아니며, 검증 경로를 런타임 테스트로 확인해야 한다.

### 2. MVC 인증 예외 처리를 공통 구조에 통합

- 근거: `src/main/java/com/pebble/api/auth/presentation/AuthExceptionHandler.java:17`.
- 응답 JSON은 공통 형식이지만 Feature 전용 `@RestControllerAdvice`를 추가한 점은 첨부안 9절과 어긋난다.
- 조치: 인증 오류의 의미는 auth에 유지하면서 공통 MVC 예외 처리 경로로 통합한다. 비슷한 오류 추상화가 실제로 필요한지 확인하고 불필요한 Interface를 만들지 않는다.
- `ApiSecurityErrorHandler`는 Filter 단계의 오류를 처리하므로 MVC advice 통합 대상과 구분한다.

### 3. API 응답 DTO를 presentation에 배치

- 근거: `src/main/java/com/pebble/api/auth/application/NaverLoginService.java:100`.
- `LoginResponse`·`MemberResponse`가 application에 정의되고 Controller에서 그대로 HTTP 응답으로 사용된다. `docs/ARCHITECTURE.md` 11절의 기본 배치와 다르다.
- 조치: 외부 응답 계약의 소유자를 presentation으로 정리한다. 내부 결과를 분리할 때 같은 DTO를 계층별로 복제하거나 별도 Mapper를 기계적으로 추가하지 않는다.

### 4. 단순 생성자 주입을 기존 Lombok 규칙으로 정리

- 대상: `NaverLoginService`, `AccessTokenService`, `NaverAuthController`, `RestClientNaverOAuthGateway`, `ApiSecurityErrorHandler`.
- 조치: 단순 필드 할당 생성자는 `@RequiredArgsConstructor`로 정리한다.
- `UserRefreshTokenService`의 pepper 검증, `OAuthStateStore`의 설정 변환, `OAuthMemberService`의 TransactionTemplate 조립 생성자는 유지할 근거가 있다.

위 네 항목은 이슈 #15의 기존 로그인 구현 정비 작업으로 묶어 수정했다.

## 현재 유지할 구조

- Package by Feature와 회원 저장 책임은 유지한다. auth가 member의 Application 계약을 사용한다.
- `global.presentation`의 공통 응답·예외·trace와 `global.security`의 기술 설정 배치는 타당하다.
- Entity 직접 API 반환과 `@Data` 사용은 확인되지 않았다. 확인한 코드 주석은 한글이다.
- 현재 반복된 createdAt·updatedAt 쌍이 여러 Entity에 존재하지 않는다. BaseEntity를 지금 강제로 추출할 근거는 없다.
- state의 브라우저 쿠키 대조 및 Redis 일회용 소비, Refresh Token 원문 대신 HMAC digest 저장, 900초 JWT 발급과 쿠키 속성은 문서 방향에 맞는다.
- 현재 비트랜잭션 로그인 호출과 동시 가입 테스트에서는 충돌 후 새 트랜잭션으로 재조회한다. 이후 외부 트랜잭션에서 `OAuthMemberService.resolve`를 호출하면 기본 REQUIRED 전파로 실패 트랜잭션을 공유할 수 있으므로 경계를 다시 확인해야 한다.

## 후속 기능 및 출시 전에 남은 보안 과제

현재 로그인 기능의 완료 범위와 전체 보안 설계의 완료를 구분한다.

- 다음 기능: Refresh 회전·재사용 탐지·로그아웃. 쿠키 요청의 Origin/CSRF 방어와 CORS 범위를 함께 구현해야 한다. 현재 전역 CSRF 비활성화를 그대로 둔 채 endpoint만 추가하면 안 된다.
- JWT 정상 키 교체: 현재 단일 키 쌍만 지원하므로 이전 유효 토큰을 검증하는 교체 구간이 구현되지 않았다.
- Pepper 교체: Redis 메타데이터에 pepper 버전이 없고 이전·현재 pepper를 함께 검증하지 않는다. Refresh 구현에서 저장 계약을 확인해야 한다.
- 로그인 속도 제한과 보안 감사 이벤트가 아직 없다. 문서의 운영 정책을 확정하고 출시 전에 구현해야 한다.
- 실제 Naver 앱·브라우저 로그인, 운영 TLS·쿠키, 비밀 주입 및 자동 수집 로그의 민감 정보 처리는 이번 정적 점검으로 검증하지 않았다.

## 모델 사용 원칙

현재 도구는 하위 Agent의 모델·reasoning을 지정할 수 있어 이번 일반 규칙 점검에 Luna를 적용했다. 실행 중인 주 모델은 이 파일만으로 자동 전환하지 않는다.

- 명확한 탐색·문서·DTO·작은 구현: Luna 우선.
- 보안·동시성·트랜잭션·문서 충돌: 주 Agent가 판단하고 핵심 경계를 검토.
- 위임 준비와 재검토 비용이 더 큰 작은 작업: 직접 처리.
- 전체 이력 복제·중복 조사·긴 출력·불필요한 테스트 반복을 줄인다. 테스트 통과만으로 검토를 전부 생략하지 않는다.

모델·reasoning별 하위 작업 구성과 AGENTS.md 로딩 방식은 [공식 하위 Agent 문서](https://learn.chatgpt.com/docs/agent-configuration/subagents)와 [공식 AGENTS.md 문서](https://learn.chatgpt.com/docs/agent-configuration/agents-md)를 참고한다.

## Git 민감정보 추가 점검

- 2026-10-01에 원격 브랜치·태그 참조를 갱신하고 현재 GitHub 참조와 대조했다. 접근 가능한 커밋 15개, 고유 텍스트 blob 87개를 패턴 및 설정 값 분류로 확인했다.
- 개인 키, 알려진 GitHub·AWS·OpenAI 자격 증명 패턴, JWT 원문, 자격 증명이 든 URL 및 민감 설정 파일 이름을 검사했다. 후보 값은 출력하지 않았다.
- `.env`, 개인 키 파일, 비밀 설정 파일이 해당 커밋 이력에 포함된 사례는 발견하지 못했다.
- Naver 실제 자격 증명과 실제 서명 키·pepper가 커밋된 사례는 발견하지 못했다. 발견한 후보는 문서 placeholder, 테스트의 쿠키 문자열 처리, 환경변수 참조와 실행 시 난수로 생성한 테스트 pepper였다.
- 저장소의 `pebble-local` 비밀번호는 Compose·CI의 개발용 예제 설정이다. 실제 운영 비밀로 사용하면 안 된다.
- 이 결과는 검사한 Git 이력과 설정 범위의 결과다. GitHub의 삭제된 참조·캐시, 모든 Issue·PR 댓글, 외부 로그·APM·계정 접근 기록까지 조사한 유출 사고 감사는 아니다. 패턴에 걸리지 않는 모든 비밀의 부재를 증명하지 않는다.
