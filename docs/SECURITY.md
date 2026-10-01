# Pebble Security Policy

이 문서는 Pebble API의 인증(Authentication), 인가(Authorization), 토큰 및 보안 자격 증명 처리 기준을 정의한다.

- 제품 기능과 사용자 역할: `PRD.md`
- HTTP API 계약과 endpoint별 접근 범위: `API.md`
- 계정 및 보안 관련 저장 구조: `DB.md`
- 보안 코드의 Feature 소유권과 의존 방향: `ARCHITECTURE.md`
- 인증·인가 구현 정책과 토큰 수명주기: 이 문서

`API.md`의 endpoint, Request/Response, HTTP Status가 외부 계약의 기준이다. 이 문서는 그 계약을 Spring Security와 애플리케이션에서 어떻게 강제할지 정의하며 API 계약을 다시 열거하지 않는다.

## 1. 보안 원칙

- 인증된 주체와 권한은 서버가 검증한다. 요청 본문이나 클라이언트가 보낸 Role, member ID, admin ID를 권한 근거로 신뢰하지 않는다.
- 회원과 관리자 계정은 서로 다른 Domain Model과 저장 구조를 사용한다. 현재 스키마는 `member` 및 `member_oauth_identity`와 `admin_account`를 분리한다.
- 일반 회원은 Naver OAuth로 인증하고, 관리자는 login ID와 password로 인증한다.
- 현재 애플리케이션은 하나의 Spring Security 기반에서 두 로그인 방식을 처리한다. 인증 방식과 계정 저장소를 분리하는 것이 보안 프레임워크 자체를 여러 개 두어야 한다는 뜻은 아니다.
- 인증, endpoint 역할 검사, 리소스 소유권, Domain 상태 전이는 서로 다른 책임으로 검사한다.
- TLS를 사용하고, 토큰·비밀번호·OAuth 자격 증명·pepper·서명 키를 로그나 저장소에 노출하지 않는다.
- USER 및 관리자 Access JWT의 만료 시간은 900초로 고정한다. Refresh Token 만료 기준은 7.1절에서 정하며, 그 밖의 미확정 운영 값은 출시 전에 결정한다.

## 2. 현재 구성과 관리자 백오피스 분리

### 2.1 현재 백엔드

현재 구조에서는 Naver OAuth 로그인과 관리자 Local Login이 같은 Pebble API 백엔드의 Spring Security에 연결된다.

- 로그인 흐름과 계정 조회는 주체 유형에 맞게 분리한다. 회원 인증은 `member` 계정 및 Naver OAuth 식별자를 사용하고, 관리자 인증은 `admin_account`를 사용한다.
- 사용자와 관리자는 같은 인증 처리 기반과 API Bearer Token 방식을 이용하되, 관리자 권한을 일반 사용자 권한으로 간주하지 않는다.
- 일반 회원 API는 Access JWT의 서명과 만료를 검증하고, 기본적으로 요청마다 Redis 세션 상태를 조회하지 않는다.
- 관리자 주체의 인증된 요청은 Access JWT의 `sid`가 Redis에서 활성 상태인지 확인한다. 관리자 세션 폐기와 계정 비활성화를 즉시 적용한다.
- `/api/v1/admin/**` 경로에는 관리자 권한을 명시적으로 요구한다. `MASTER` 전용 동작은 별도로 제한한다.
- 필요하면 하나의 애플리케이션 안에서 경로별로 여러 `SecurityFilterChain`을 구성할 수 있다. 체인을 나누더라도 모든 요청이 의도한 체인에 매칭되는지 검증하고, 보호되지 않은 요청이 남지 않게 한다.

회원과 관리자 계정의 저장 구조를 분리한 것은 계정 데이터와 Domain 책임을 분리한 결정이다. 현재 `DB.md`는 같은 PostgreSQL 설계 안에 별도 테이블을 정의한다. 이 분리만으로 Spring Security 설정이나 토큰 발급자를 지금 별도 시스템으로 나눌 필요는 없다.

### 2.2 백오피스 프런트엔드만 별도 프로젝트로 분리하는 경우

관리자 UI만 별도 프런트엔드 프로젝트로 배포하고 API 백엔드를 공유한다면 백엔드 인증 구조는 유지한다. 백엔드에 관리자 인증과 `/api/v1/admin/**` 접근 정책이 계속 존재한다.

- 새 프런트엔드의 정확한 Origin만 CORS 허용 목록에 추가한다. 임의 Origin을 허용하거나, 자격 증명과 와일드카드 Origin을 함께 사용하지 않는다.
- 새 프런트엔드도 현재 API 계약을 따른다. Access JWT는 메모리에 두고 Bearer Header로 보내며 Refresh Token은 HttpOnly Cookie로 처리한다. BFF를 추가하거나 전송 방식을 바꾸면 API, CORS, CSRF 계약을 함께 갱신한다.
- UI 프로젝트 분리는 독립적인 인증 시스템을 만들어야 한다는 의미가 아니다.

### 2.3 관리자 백엔드/API까지 별도 서비스로 분리하는 경우

백오피스가 별도 백엔드 서비스가 되면 Spring Security를 각 서비스에 포함할 수 있지만, 인증 시스템을 중복 구현할 필요는 없다. 권장 출발점은 중앙 인증/토큰 발급 책임을 두고 각 API 서비스를 Resource Server로 운영하는 것이다.

- 토큰 발급자는 서명 키의 private key를 보관하고, API 서비스는 신뢰할 수 있는 공개 키로 Access JWT를 검증한다.
- 각 Resource Server는 서명 외에도 `iss`, 자기 서비스에 지정된 `aud`, 만료 및 유효 시작 시각을 검증한다. Admin API에는 Admin API용 Audience를 요구한다.
- Admin API는 `MANAGER`와 `MASTER` 권한 및 관리자 계정 상태를 독립적으로 강제한다. User API의 권한 검사를 Admin API에 대신 맡기지 않는다.
- 관리자 `sid`의 온라인 검사는 중앙 세션 검증 서비스 또는 최소 권한으로 제한한 세션 저장소 접근을 통해 유지한다. 분리된 서비스마다 범용 Redis 관리자 자격 증명을 공유하지 않는다.
- 별도 배포, 별도 `SecurityFilterChain`, 별도 Resource Server 설정은 가능하지만, 관리자 계정·암호 검증·Refresh Token 정책을 복제하지 않는다.
- 관리자 전용 발급자, 키, 로그인 세션을 따로 두는 결정은 규제·네트워크 경계·운영 책임이 실제로 분리될 때 검토한다. 이 경우에도 발급자, Audience, 키 교체, 토큰 폐기, 계정 동기화 정책을 함께 설계한다.

## 3. 인증과 인가 책임

| 책임 | 담당 | 예 |
|---|---|---|
| 인증 | Spring Security와 인증 흐름 | Naver 로그인 결과 또는 관리자 password를 확인하고 인증된 주체를 만든다. |
| 역할 기반 HTTP 접근 | Spring Security | 관리자 endpoint에 `MANAGER` 또는 `MASTER` 권한을 요구한다. |
| 관리자 세션 유효성 | Spring Security 보안 계층 | 관리자 주체의 요청마다 Redis에서 `sid` 활성 여부를 확인한다. |
| 유스케이스 접근 및 리소스 관계 | Application | 정지 계정 차단, 요청자가 콘텐츠 소유자인지 확인하고 유스케이스를 조정한다. |
| 비즈니스 상태 전이 | 소유 Domain | Post 차단/공개 상태 또는 회원 상태 변경이 규칙상 가능한지 확인한다. |

인증 성공은 모든 동작이 허용되었다는 뜻이 아니다. URL 역할 검사는 소유권·계정 상태·콘텐츠 공개 상태 검사를 대체하지 않는다. 다른 Feature의 Domain 상태를 관리자 API에서 직접 변경하지 않고 소유 Feature의 책임을 통해 처리한다.

`GET /api/v1/categories`와 `GET /api/v1/tags`는 Guest 공개 탐색 조회다. 정확히 이 두 GET 경로만 미인증 접근을 허용하며, 프런트엔드 허용 Origin의 GET CORS를 지원한다. Category·Tag의 관리자 경로와 일반 회원 쓰기 경로는 아직 구현하지 않았고 계속 거부한다. 분류 조회를 위해 기존 OAuth·Refresh·logout의 쿠키 Origin 방어나 전역 CSRF 정책을 변경하지 않는다.

현재 `GET /api/v1/members/me`는 검증된 USER JWT의 role을 `ROLE_USER`로 변환해 HTTP 접근을 허용하고, member Application이 본인 계정을 조회해 현재 DB 상태가 ACTIVE인지 확인한다. 정지·탈퇴 대기 상태는 각각 `ACCOUNT_SUSPENDED`·`ACCOUNT_WITHDRAWAL_PENDING` 403으로 거부한다. 토큰에 포함된 회원 ID 외에 요청으로 조회 대상을 지정할 수 없으며, Refresh Cookie는 이 조회의 인증 수단이 아니다. JWT 서명·claim 검증과 Redis 세션 정책은 그대로 유지한다.

## 4. 주체와 권한

현재 외부 역할 이름의 기준은 `API.md`다.

| 주체 | 계정 | 권한 이름 | 기본 범위 |
|---|---|---|---|
| 일반 회원 | `member` | `USER` | 활성 회원 기능과 본인 리소스 관리 |
| 운영 관리자 | `admin_account` | `MANAGER` | 콘텐츠 검수와 운영 기능 |
| 최고 관리자 | `admin_account` | `MASTER` | MANAGER 기능과 관리자 계정 관리 |

Spring Security 설정에서는 명시적인 authority를 사용한다. 예를 들어 역할 기반 규칙은 `ROLE_USER`, `ROLE_MANAGER`, `ROLE_MASTER`에 대응한다. `MASTER`가 `MANAGER` 전용 API도 호출할 수 있는지는 endpoint 정책에 명시하며, 관리자라는 이유만으로 USER 상호작용 권한을 부여하지 않는다.

- 관리자 계정 생성은 인증된 `MASTER` 작업으로 제한한다. `MASTER` 초기 생성은 공개 회원가입이나 일반 관리자 생성 API로 제공하지 않는다.
- Role은 사용자 입력이나 Request DTO에서 받지 않는다. 회원은 `USER`, 관리자 계정은 DB에 저장된 `MANAGER` 또는 `MASTER` 권한을 인증 과정에서 부여한다.
- JWT의 Role은 서명된 서버 발급 Claim으로만 읽는다. Role 변경 또는 관리자 비활성화 시 기존 세션 폐기 정책을 적용한다.

## 5. 로그인과 자격 증명

### 5.1 일반 회원: Naver OAuth

- Naver OAuth Authorization Code 흐름으로 공급자 사용자를 인증한다.
- 서버가 암호학적 난수로 OAuth `state`를 만들고 짧은 만료 시간을 둔 일회용 저장소에 보관한다. 브라우저에는 HttpOnly·Secure·SameSite=Lax 쿠키로 설정하고, callback에서 받은 state·쿠키·저장소 값을 모두 대조한 뒤 한 번만 소비한다. 검증하지 못한 callback은 공급자 token API에 전달하지 않는다.
- Naver Authorization Code 교환 요청에도 검증한 `state`를 전달한다. Provider와 Client가 지원하면 PKCE도 사용한다.
- 공급자 인증 결과는 `member_oauth_identity`의 Provider와 고유 subject로 회원에 연결한다. 이메일, 닉네임 등 프로필 값만으로 계정을 연결하지 않는다.
- Naver Access Token 등 Provider 자격 증명은 Pebble API 토큰과 구분한다. 필요하지 않으면 저장하지 않는다.
- OAuth 인증이 끝나면 Pebble 자체의 Access JWT와 Refresh Token을 발급한다. Naver 토큰을 Pebble API Bearer Token으로 받지 않는다.

### 5.2 관리자: Local Login

- 관리자 login ID와 password는 별도 `admin_account` 레코드로 검증한다.
- `ACTIVE` 관리자만 로그인할 수 있다. `INACTIVE` 계정은 새 Access JWT나 Refresh Token을 발급받지 못한다.
- 로그인 실패 응답은 계정이 존재하는지 여부를 드러내지 않는다. 로그인 및 Refresh 요청은 속도 제한과 보안 감사 기록을 적용한다.
- 관리자 생성과 상태 변경은 `API.md`가 정한 `MASTER` 전용 흐름을 따른다. 고정된 관리자 계정을 애플리케이션 시작 때 무조건 생성하지 않는다.

### 5.3 관리자 비밀번호 저장

- 평문 또는 복호화 가능한 형태로 저장하지 않는다. DB에는 적응형 password hash만 저장한다.
- Spring Security `PasswordEncoder`를 사용하고, 신규 비밀번호에는 OWASP 권고를 충족하는 Argon2id 설정을 우선한다. 플랫폼 요건상 사용할 수 없으면 적절히 구성한 bcrypt 등 지원되는 적응형 해시를 선택한다.
- Salt는 해시 구현이 제공하는 고유 Salt 처리에 맡긴다. SHA-256 같은 빠른 일반 해시만으로 password를 저장하지 않는다.
- Password pepper를 추가하는 경우 Refresh Token pepper와 별도 비밀로 운영하고 DB 밖의 Secret Manager/HSM에 둔다. Password pepper를 교체하거나 유출되면 사용자의 비밀번호 재설정이 필요할 수 있다. 운영 가능한 비밀 보관·교체 절차가 없으면 password pepper를 형식적으로 추가하지 않는다.

## 6. Access JWT

Access Token은 JWT로 발급하고 API 호출 시 `Authorization: Bearer`로 전달한다. 현재 API 계약은 `API.md`를 따른다.

- JWT는 서명된 토큰이며 암호화된 데이터라고 간주하지 않는다. Claim에는 계정 비밀번호, OAuth Token, 개인정보를 넣지 않는다.
- Claim에는 최소한 발급자(`iss`), 주체(`sub`), 역할, 발급 시각(`iat`), 만료(`exp`), 유효 시작 시각(`nbf`), Audience(`aud`), 고유 Token ID(`jti`)를 포함한다.
- 관리자 Access JWT에는 세션 단위 즉시 폐기를 위해 `sid`를 포함한다. 일반 회원 Access JWT는 기본 정책에서 매 요청 Redis 상태 검사를 하지 않으므로 `sid` 검사를 강제하지 않는다.
- 회원 ID와 관리자 ID는 서로 다른 저장소에서 같은 값이 될 수 있으므로 주체 유형을 명시한다. 예를 들어 `sub`를 `member:<id>` 또는 `admin:<id>`처럼 네임스페이스화하거나 동등하게 충돌을 방지하는 검증 가능한 Claim을 사용한다.
- Resource Server는 서명, 허용 알고리즘, 예상 `iss`, `aud`, `exp`, `nbf`, 주체 유형 및 토큰 종류를 검증한다. 요청 토큰이 제공하는 키 URL이나 알고리즘을 그대로 신뢰하지 않는다.
- 현재 USER 검증기는 필수 claim의 존재와 식별자 타입을 확인한다. `sub`는 양수 BIGINT 범위의 `member:<id>`이며 `jti`는 비어 있지 않은 문자열이어야 한다. `exp`는 `iat`·`nbf` 이후이고 발급 수명은 900초 이하여야 한다. 시간 검증에는 기본 60초 clock skew를 적용한다.
- 여러 백엔드 서비스로 나눌 가능성을 고려해 비대칭 서명 방식을 우선 검토한다. 발급자만 private key를 보관하고, 검증 서비스에는 public key만 제공한다. 알고리즘, 키 크기, 키 저장소는 구현 시 확정한다.
- 키 식별자(`kid`)를 이용한 교체를 지원하고, 정상 교체 기간에는 신규 키로 발급하면서 아직 유효한 기존 토큰을 검증할 수 있게 한다. 유출 시에는 해당 키를 즉시 폐기하고 영향을 받은 세션을 만료시킨다.
- USER와 관리자 Access JWT의 만료 시간은 발급 시점부터 900초(15분)다. 로그인 및 Refresh 응답의 `accessTokenExpiresIn`은 초 단위로 `900`을 반환한다. Refresh Token의 주체별 유휴·절대 만료는 7.1절 정책을 따른다.
- Spring Security OAuth2 Resource Server 기능을 사용해 Bearer JWT의 디코딩, 검증, `SecurityContext` 설정을 처리하는 것을 우선한다. 검증 책임이 불명확한 자체 Filter를 추가하지 않는다.

JWT 서명은 Claim이 위조되지 않았음을 보장하지만 이미 발급된 토큰의 즉시 폐기를 자동으로 보장하지 않는다.

- **일반 회원:** 요청마다 Redis 세션 조회를 하지 않는다. 로그아웃은 Refresh Token Family를 즉시 폐기하지만, 이미 발급된 Access JWT는 만료 시각까지 유효할 수 있다. 이 동작은 `API.md`에 반영되어 있다. 추후 회원 Access JWT도 즉시 폐기해야 한다는 요구가 생기면 `jti` 블랙리스트 또는 세션 상태의 요청별 조회를 추가하고 API 계약을 갱신한다.
- **관리자:** `sid`를 Access JWT에 포함하고, 관리자 주체의 모든 인증 요청에서 Redis의 해당 세션 활성 상태를 확인한다. 로그아웃, 계정 비활성화, Role 변경 또는 보안 사고 시 해당 관리자 세션을 폐기한다. 관리자 API는 저빈도·고권한 경로이므로 이 온라인 확인을 적용한다.

로그아웃된 `jti`만 Redis에 기록하는 블랙리스트는 저장 공간을 줄일 수 있지만, 요청 토큰이 폐기 목록에 있는지 판별하려면 보호 요청마다 조회해야 한다. 따라서 Access JWT의 즉시 폐기에는 온라인 Redis 확인 비용이 필요하다. `sid`는 한 세션에서 발급한 여러 Access JWT를 한 번에 폐기하기 쉬운 반면, `jti` 블랙리스트는 해당 세션의 다른 유효 토큰도 모두 처리할 수 있도록 추가 관계 관리가 필요할 수 있다.

## 7. Refresh Token과 Redis

### 7.1 형식과 저장

권장 구조는 **짧은 수명의 서명 Access JWT와 고엔트로피 불투명(opaque) Refresh Token**을 조합하는 것이다. Refresh Token 자체를 JWT로 만들지 않는다. 회전, 재사용 감지, 세션 단위 폐기를 Redis 상태로 명확히 관리하기 쉽다.

- Refresh Token은 CSPRNG로 생성한 예측 불가능한 임의 값으로 만들고 인증 자격 증명처럼 취급한다. 충분한 엔트로피를 확보하며, ID나 시각을 토큰에 인코딩하지 않는다.
- 원문 Refresh Token을 DB, Redis, 로그, 분석 이벤트에 저장하지 않는다.
- Redis 조회 값은 `HMAC-SHA-256(refreshTokenPepper, refreshToken)`로 계산한 digest를 사용한다. 일반 SHA-256만으로 digest를 저장하는 것보다 별도 비밀 키를 요구한다.
- 신규 USER 로그인은 Refresh Token 원문을 CSPRNG로 생성해 브라우저 쿠키에만 반환한다. Redis에는 digest를 키로 사용하고 세션 메타데이터를 저장한다. digest 계산에는 32바이트 이상 Refresh Token pepper를 HMAC-SHA-256으로 적용한다.
- Redis에는 Token digest, 주체 유형·ID, 세션 ID, Token Family ID, 상태, Token 발급 시각, 마지막 성공 사용 시각, Family 최초 로그인 시각(`familyCreatedAt`), 비활성 만료 시각(`idleExpiresAt`), 절대 만료 시각(`absoluteExpiresAt`)을 저장한다. `familyCreatedAt`과 `absoluteExpiresAt`은 Token 회전 때 바꾸지 않는다. 키 이름에도 원문 Token을 넣지 않는다.
- Refresh Token 만료 정책은 다음과 같다. `idle`은 성공한 Refresh Token 사용으로 연장하고, `absolute`는 최초 로그인 시각부터 연장하지 않는다. MANAGER와 MASTER에는 같은 관리자 정책을 적용한다.

| 주체 | 비활성 만료 | 절대 만료 |
|---|---:|---:|
| USER | 14일 | 최초 로그인부터 30일 |
| 관리자 (MANAGER, MASTER) | 1시간 | 최초 로그인부터 24시간 |

- Redis의 유효 만료 시각과 쿠키 `Max-Age`는 `min(마지막 성공 refresh 시각 + 비활성 만료, 최초 로그인 시각 + 절대 만료)`를 따른다. 회전할 때 비활성 만료는 갱신하지만 최초 로그인 기준 절대 만료는 연장하지 않는다. 만료된 Family는 Refresh를 거부하고 새 로그인을 요구한다.
- 클라이언트는 사용하지 않는 세션을 주기적인 백그라운드 refresh로 연장하지 않는다. 비활성 시간은 마지막 성공 Refresh Token 사용부터 계산한다.
- 관리자 만료는 고권한 계정의 재인증 간격을 짧게 두기 위한 정책이다. NIST AAL2의 참고 시간인 비활성 1시간·전체 24시간을 기준으로 삼았으며, 관리자 로그인에 MFA가 없는 현재 설계가 AAL2를 충족한다는 의미는 아니다.
- Redis는 인증 상태 저장소다. 내부 네트워크에 격리하고 TLS와 ACL을 적용하며, 명령 접근을 필요한 애플리케이션 계정으로 제한한다.

### 7.2 회전과 재사용 감지

Refresh 요청이 성공할 때마다 기존 Token을 한 번만 사용할 수 있게 소비하고, 같은 세션의 새 Refresh Token과 Access JWT를 발급한다.

1. 기존 Refresh Token의 digest와 세션·계정 상태를 확인한다.
2. Redis 원자 연산(Lua Script 또는 동등한 Transaction)으로 기존 Token을 `CONSUMED` 처리하고 새 Token digest를 저장한다. 다른 동시 요청이 기존 Token을 다시 유효한 것으로 사용할 수 없어야 한다.
3. 기존 Token의 소비 기록은 Token Family 만료까지 보관한다. 폐기된 Token을 다시 제출하면 재사용으로 판정할 수 있어야 한다.
4. 이미 소비된 Refresh Token이 다시 제출되면 Token Family와 그 세션의 활성 Refresh Token을 폐기하고 보안 이벤트를 기록한다. 공격자와 정상 클라이언트를 구분할 수 없으므로 사용자는 다시 로그인해야 할 수 있다.
5. 클라이언트는 동일 세션에서 Refresh 요청을 동시에 보내지 않도록 단일화한다. 응답 유실 뒤 이전 Token을 재전송하는 동작도 재사용 탐지로 처리될 수 있다.

현재 USER 구현은 단일 Redis 인스턴스의 Lua Script로 토큰 상태 검사·소비·후속 digest 저장 또는 Family 폐기 표식 저장을 한 번에 처리한다. 개별 digest hash와 Family digest set은 기존 초기 세션 형식을 유지한다. Family 폐기 표식이 있으면 모든 후속 갱신이 거부되어 활성 digest도 즉시 논리적으로 폐기된다. 소비 hash와 폐기 표식은 절대 만료까지 보관하며 활성 hash는 유휴·절대 만료 중 이른 시각에 만료한다. Lua는 Redis TIME으로 만료를 다시 검사한다. Family set이 사라진 경우도 갱신을 거부한다. 토큰 원문을 복구하거나 응답 유실 유예를 제공하지 않는다. 동시 요청의 첫 회전 성공 응답이 늦게 도착해도 이후 재사용 요청으로 그 Family가 폐기될 수 있다. 클라이언트는 재로그인한다.

조회한 회원 상태는 회전 직전에 member Application 계약으로 확인하고 비활성 상태라면 제출된 Family를 폐기한다. PostgreSQL 회원 상태 조회와 Redis 전이는 분산 트랜잭션이 아니다. 향후 탈퇴·상태 변경 기능은 7.3절의 전체 Family 폐기 조정을 구현해야 하며, 이번 USER refresh/logout 기능이 전체 회원 세션 폐기 기능을 대신하지 않는다. Redis Cluster는 동적 Family 키와 digest 키의 같은 hash slot을 보장하지 않으므로 현재 지원하지 않는다. Cluster 전환 시 키 설계와 원자성 검증을 함께 변경한다.

토큰 교체와 재사용 판정은 하나의 원자적 상태 전이여야 한다. Redis의 개별 읽기와 쓰기를 순서대로 실행하는 것만으로 회전 동시성을 보장하지 않는다.

### 7.3 로그아웃과 계정 상태 변경

- 회원 로그아웃은 Refresh Token Family를 폐기한다. 온라인 Access Token 검사를 선택하지 않은 동안 이미 발급된 회원 Access JWT는 만료 시각까지 유효할 수 있다.
- 관리자 로그아웃은 Refresh Token Family와 Redis의 활성 `sid`를 폐기한다. 이후 해당 세션으로 발급된 관리자 Access JWT는 다음 요청부터 거부한다.
- 회원 탈퇴, 관리자 비활성화, 비밀번호 변경, 역할 변경 또는 보안 사고 시 해당 주체의 Refresh Token Family를 폐기한다. 관리자 주체는 연관된 활성 `sid`도 폐기한다. 현재 `API.md`가 정의한 회원·관리자 상태 전이와 동기화한다.
- 재사용이 감지된 Family의 폐기는 해당 Family로 발급된 모든 Refresh Token에 적용한다.
- Redis에 세션 폐기 상태를 확인할 수 없을 때 관리자 인증 요청과 Refresh 요청은 유효한 것으로 간주하지 않는다. 무조건 허용하는 fail-open 동작을 추가하지 않는다. 일반 회원 Access JWT 검증은 기본 정책에서 Redis에 의존하지 않는다.
- Redis 장애 시 로그인/갱신/폐기 동작과 보호 요청의 가용성 정책은 운영 설계에서 정한다. 다만 장애 때문에 원문 토큰을 다른 저장소나 로그에 우회 저장하지 않는다.

## 8. Pepper와 비밀 키 관리

### 8.1 Refresh Token pepper

Refresh Token digest용 pepper는 Refresh Token을 Redis에 보관할 때 HMAC 키로 사용한다.

- 암호학적으로 안전한 난수로 만들고 Secret Manager 또는 HSM에 보관한다. 소스 코드, 저장소, DB, Redis에 넣지 않는다.
- JWT signing key, OAuth Client Secret, 관리자 Password pepper와 서로 다른 비밀을 사용한다.
- 저장된 digest와 함께 pepper 자체를 저장하지 않는다. 어떤 pepper 버전으로 계산했는지 식별 가능한 버전만 저장한다.
- 정상 교체는 이전·현재 pepper를 제한된 교체 기간 동안 검증할 수 있게 준비하고, 이전 pepper로 일치한 활성 토큰은 성공적인 회전 시 현재 pepper로 다시 저장한다. 이전 키 제거 시점은 최대 Refresh Token 수명 이후로 정한다.
- pepper 유출이 의심되면 이전 버전으로 계산된 Refresh Token 세션을 폐기하고 새 pepper로 재로그인하게 한다.

현재 설정은 `REFRESH_TOKEN_PEPPER_BASE64`(현재 비밀)와 `REFRESH_TOKEN_PEPPER_VERSION`(기본 `v1`)을 사용한다. 이전 비밀은 보호된 설정 주입으로 `pebble.auth.refresh-token.previous-peppers`의 버전별 Base64 map에 제공한다. 예를 들어 현재 버전을 `v2`로 전환할 때 `previous-peppers.v1`에 기존 비밀을 주입한다. 저장소 설정에는 실제 map 비밀을 작성하지 않는다. 버전은 변경한 키마다 고유하게 지정하고 같은 버전에 다른 비밀을 덮어쓰지 않는다. 버전 없는 기존 초기 hash는 `v1`로만 해석한다. 이전 활성 토큰은 현재 pepper로 회전하며 같은 Family·세션·절대 만료를 유지한다. 이전 소비 digest의 재사용 탐지를 위해 모든 노드에 이전 pepper를 마지막 이전 버전 발급 이후 최소 30일 동안 유지한다. 롤링 배포에서는 새 버전을 모든 노드가 검증할 수 있게 먼저 배포한 뒤 발급 버전을 전환한다.

유출된 버전은 정상 교체 기간을 적용하지 않고 검증 설정에서 제거하여 해당 digest를 즉시 거부한다. 그 버전의 세션 Family 폐기는 내부 운영 절차로 Redis Family 폐기 표식을 절대 만료까지 설정한다. 이미 회전된 Family까지 폐기해야 하는 사고라면 관련 Family 전체를 식별해 폐기해야 한다. 자동 사고 대응·전체 세션 관리 도구와 Secret Manager 담당자·복구 절차 확정은 출시 전 별도 작업이다. JWT signing key는 이 설정과 독립적이며 기존 RS256 키 검증 정책을 변경하지 않는다.

Pepper는 토큰의 충분한 난수성, 안전한 보관, TLS, 짧은 Access Token 만료 또는 회전 정책을 대체하지 않는다.

### 8.2 다른 비밀

- JWT 서명 키와 Naver OAuth Client Secret도 Secret Manager/HSM 또는 배포 환경의 보호된 비밀 주입 경로로 제공한다.
- JWT 키 입력은 한 줄 Base64 DER(`JWT_PRIVATE_KEY_BASE64`: PKCS#8, `JWT_PUBLIC_KEY_BASE64`: X.509)만 지원한다. PEM 파일 경로는 받지 않는다. 신규 키는 RSA 3072비트로 생성하며 표준 최소값인 2048비트 이상을 검증한다. 누락·잘못된 인코딩·불일치 키·2048비트 미만 RSA 키는 애플리케이션 시작에서 거부한다. 키 설정의 문자열 표현과 디코딩 오류에 키 원문을 노출하지 않는다. Base64는 암호화가 아니며 파일 배포를 줄이는 전송 형식일 뿐이다. 운영에서는 별도 비밀 저장·접근 통제와 환경별 키 분리를 유지한다. 서명·검증 알고리즘은 RS256으로 명시 고정하고 서명 없는 토큰이나 다른 알고리즘은 거부한다. claim·kid 정책은 그대로이며 다중 검증 키를 통한 정상 교체는 별도 후속 작업이다.
- 개발·스테이징·운영 환경에서 비밀을 분리하고 공유하지 않는다.
- 운영 비밀을 `.env`, 예제 설정, 로그, 이슈 본문 또는 Git에 기록하지 않는다. 저장소에 실제 비밀이 유입되면 파일에서 지우는 것만으로 끝내지 말고 해당 비밀을 교체한다.

## 9. 브라우저 전송, CORS와 CSRF

- API의 Access Token 전달 방식은 `Authorization: Bearer`다. TLS 없이 토큰을 주고받지 않는다.
- Access JWT는 로그인·갱신 응답 본문으로 전달하고 클라이언트 메모리에만 보관한다. 보호된 API에는 `Authorization: Bearer`로 보낸다. `localStorage`나 `sessionStorage`에 저장하지 않는다.
- Refresh Token은 별도 USER/Admin 쿠키에만 담아 전달한다. 쿠키는 `HttpOnly; Secure; SameSite=Lax`로 설정하고 API 계약처럼 브라우저가 refresh·logout 요청에 자동으로 포함한다. JSON 본문에 Refresh Token을 넣지 않는다.
- Cookie가 자동으로 전송되는 refresh·logout 요청에는 CSRF 방어를 적용한다. `SameSite`는 방어의 한 겹으로 사용하고, 허용 Origin 검증과 필요 시 CSRF Token을 함께 적용한다.
- USER refresh/logout POST는 허용 CORS 목록과 정확히 같은 단일 Origin을 필수로 검증한다. 누락·null·중복·불허 Origin은 쿠키 사용과 Redis 변경 이전에 공통 403으로 거부한다. SameSite=Lax와 필수 Origin 검증을 함께 사용하는 현재 계약에서는 별도 CSRF Token을 요구하지 않는다. 비브라우저 호출도 Origin 계약을 따른다. Origin 검사 제외·SameSite 변경·새 Cookie 인증 경로 추가 시 CSRF 정책을 다시 검토한다.
- 전역 CSRF는 활성화한다. POST Naver authorization/login은 OAuth state 검증 흐름, USER refresh/logout은 별도 필수 Origin 필터를 적용한 명시적 예외다. 그 밖의 경로는 기본 CSRF 검사와 기존 접근 거부 정책을 유지한다.
- CORS는 필요한 Origin, Method, Header만 허용한다. 쿠키 Credential을 허용할 때는 와일드카드 Origin을 사용하지 않는다.
- 현재 기본값은 같은 사이트 배포를 위한 `SameSite=Lax`다. UI와 API가 서로 다른 사이트에 배포되어 `SameSite=None`이 필요한 경우에도 `Secure`를 유지하고 CSRF 방어와 정확한 Credential 허용 Origin을 적용한다. 가능한 경우 Cookie `Domain`은 지정하지 않는다.

## 10. 오류, 로깅과 운영

- 외부 인증 오류는 `API.md`의 HTTP Status와 Error Code 계약을 따른다. 로그인 실패 메시지로 계정 존재 여부, 비밀번호 정답 여부를 구분하지 않는다.
- Access Token, Refresh Token, Authorization Header, password, OAuth code, pepper, 서명 키를 로그에 기록하지 않는다. 예외 및 APM 수집에도 원문 자격 증명이 포함되지 않게 한다.
- MANAGER·MASTER 등 개인정보취급자의 개인정보처리시스템 접속 및 주요 행위는 별도 관리자 접속·감사기록으로 남긴다. 인증 주체, 시각, 접속 위치(IP), 작업 종류와 결과, 접근한 데이터 종류, 필요한 경우 최소화한 대상 참조값, 다운로드 여부 등 추적에 필요한 항목만 기록한다. 요청·응답 본문이나 실제 개인정보 값은 넣지 않으며 원문 토큰, OAuth code, 비밀번호는 어떤 경우에도 기록하지 않는다.
- 관리자 접속기록은 이벤트 발생일부터 **1년 이상** 보관한다. 해당 시스템이 5만 명 이상의 정보주체 정보를 처리하거나, 고유식별정보 또는 민감정보를 처리하거나, Pebble 운영 주체가 기간통신사업자에 해당하는 경우에는 **2년 이상** 보관한다. Pebble의 기본 정책은 1년이며, 배포 전과 운영 중 위 조건의 해당 여부를 확인하고 하나라도 해당하면 2년 이상으로 적용한다.
- 접속기록은 회원 콘텐츠와 분리된 접근 제한 저장소에 보관하고 위·변조 및 무단 삭제를 방지한다. 내부 관리계획에 따라 정기적으로 점검하고, 보유기간이 끝나면 법적 보존 근거가 없는 항목을 파기한다. 일반 디버그·성능 로그에 접속기록 보유기간을 일괄 적용하지 않는다.
- 로그인 성공/실패, Refresh Token 재사용, 관리자 로그인, 계정 비활성화, 세션 폐기와 보안 설정 변경은 필요한 최소 정보로 감사 기록을 남긴다. 토큰 원문 대신 내부 주체 ID, 이벤트, 시간, 결과, 추적 ID를 기록한다.
- 로그인과 Refresh endpoint에 계정·IP 단위 속도 제한 및 비정상 시도 감지를 적용한다. 임계값과 잠금·지연 동작은 운영 정책에서 확정한다.
- Production 오류 응답에는 Stack Trace, SQL, 내부 경로, 공급자 응답 전문, 비밀 정보를 포함하지 않는다.
- 관리자 인증 및 관리자 API에는 별도 모니터링과 접근 감사 정책을 적용한다. 관리자 권한은 필요한 API에만 부여한다.

## 11. 출시 전에 확정할 결정

다음 구현 세부사항은 코드·배포 환경과 함께 확정한다. 관리자 접속기록 보유기간은 10절의 정책을 따른다.

1. 서명 알고리즘, 키 크기, Key ID 형식, Secret Manager/HSM 및 키 교체 절차
2. 관리자 Redis 토큰 Family 구현 및 Redis Cluster 전환 시 키·원자성 설계 (USER 정책은 7.2절에서 구현)
3. Refresh Token pepper와 선택적 Password pepper의 관리·교체 담당자 및 장애 복구 절차
4. 로그인 속도 제한, 계정별 잠금/지연 수치와 비접속 운영 로그의 목적별 보유기간
5. Admin API가 별도 백엔드로 분리될 경우 Token Issuer, Audience, 관리자 전용 세션과 키 경계

## 12. 참고 기준

- [Spring Security OAuth2 지원](https://docs.spring.io/spring-security/reference/servlet/oauth2/): OAuth2 Client, Resource Server, JWT Bearer Token 처리
- [Spring Security Java Configuration](https://docs.spring.io/spring-security/reference/servlet/configuration/java.html): 여러 `SecurityFilterChain`의 요청 매칭과 우선순위
- [RFC 9700: OAuth 2.0 Security Best Current Practice](https://www.rfc-editor.org/rfc/rfc9700.html): Refresh Token Rotation 및 재사용 탐지
- [NIST SP 800-63B-4, Authentication and Authenticator Management](https://pages.nist.gov/800-63-4/sp800-63b.html): AAL별 세션 재인증 기준 시간
- [RFC 8725: JSON Web Token Best Current Practices](https://www.rfc-editor.org/rfc/rfc8725.html): JWT 검증, 발급자·대상 검증, Claim 혼동 방지
- [OWASP JSON Web Token Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/JSON_Web_Token_Cheat_Sheet.html): JWT 폐기, 민감 Claim 및 토큰 노출
- [OWASP Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html): 적응형 비밀번호 해시와 Pepper
- [OWASP Session Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html): 브라우저 토큰 저장과 Cookie 고려사항
- [Redis SET command](https://redis.io/docs/latest/commands/set/): TTL을 포함한 Redis 저장 동작
- [개인정보의 안전성 확보조치 기준 제8조](https://www.law.go.kr/LSW/admRulSideInfoP.do?admRulSeq=2100000281400&chrClsCd=010201&dashNo=&docCls=jo&joBrNo=00&joNo=0008&urlMode=admRulScJoRltInfoR): 개인정보처리시스템 접속기록의 1년/2년 보관 및 점검
- [개인정보 보호법 제21조](https://www.law.go.kr/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1034516739): 개인정보가 불필요해진 경우 파기 및 법령상 보존 정보의 분리 관리
