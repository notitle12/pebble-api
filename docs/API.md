# Pebble API

## 1. 범위와 기준

이 문서는 Pebble MVP의 HTTP API 계약을 정의한다.

- 제품 기능과 역할: PRD.md
- Feature 소유권과 협력: ARCHITECTURE.md
- 저장 구조와 제약: DB.md
- API 기본 경로: /api/v1
- 요청과 응답은 별도 표기가 없으면 application/json UTF-8을 사용한다.
- 예시의 필드명은 JSON camelCase를 사용한다.
- Post와 Project는 독립된 리소스와 API를 사용한다. 댓글과 좋아요도 대상이 명확한 하위 경로를 사용한다.
- MVP 범위 밖인 신고, 공지, 대댓글, 북마크, 팀 프로젝트 협업 API는 제공하지 않는다.

JWT 서명 검증과 토큰 저장·폐기 구현 정책은 SECURITY.md에서 정한다. 이 문서는 인증 토큰의 응답 전달 방식과 endpoint별 HTTP 계약을 정의한다.

## 2. 공통 규칙

### 2.1 식별자와 시각

- PostgreSQL BIGINT/TSID 식별자는 JSON에서 10진 문자열로 주고받는다. 예: "721389012345678901".
- 응답의 날짜·시각은 UTC RFC 3339 형식이다. 예: "2026-09-30T03:00:00Z".
- 날짜만 사용하는 값은 ISO 8601 YYYY-MM-DD 형식이다.
- 요청에서 생략된 선택 필드는 변경하지 않는다. PATCH에서 명시적으로 null을 보내면 해당 nullable 값을 비운다.
- 배열 필드를 PATCH에서 보내면 기존 관계/항목 전체를 요청 배열로 교체한다. 순서가 있는 배열은 배열 순서를 새 표시 순서로 저장한다.

### 2.2 성공 응답

JSON 본문을 반환하는 성공 응답은 data 래퍼를 사용한다.

~~~json
{
  "data": {
    "id": "721389012345678901"
  }
}
~~~

- 단일 리소스 생성은 201 Created와 Location 헤더를 반환한다.
- 조회와 수정은 200 OK를 반환한다.
- 일반 삭제와 성공한 로그아웃은 204 No Content를 반환하며 본문은 없다. 회원 탈퇴 예약은 취소 가능 기간과 삭제 예정 시각을 안내하기 위해 202 Accepted와 data 본문을 반환한다.
- OAuth 공급자 인증 코드 처리 응답 본문에는 Access Token을 반환하고 Refresh Token은 HttpOnly Cookie로 설정한다.
- 외부 OAuth 인증 시작은 서버에서 발급받은 URL로 클라이언트가 이동한다. 등록된 callback에서 받은 authorization code와 state를 로그인 API에 전달한다.

### 2.3 오류 응답

오류는 아래 공통 구조를 사용한다. 서버 내부 예외, SQL, 토큰 또는 개인정보는 응답에 포함하지 않는다.

~~~json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "요청 값을 확인해 주세요.",
    "details": [
      {
        "field": "title",
        "reason": "최대 200자까지 입력할 수 있습니다."
      }
    ],
    "traceId": "01J..."
  }
}
~~~

- details는 입력 오류가 있을 때 필드별 항목을 반환하고, 그 외에는 빈 배열이다.
- traceId는 요청마다 생성하는 ULID이며 서버 로그와 오류를 연결한다.
- message는 사용자에게 표시할 수 있는 설명이며, code는 클라이언트 분기용 안정 식별자다.

예기치 않은 서버 오류의 code는 `INTERNAL_ERROR`를 사용한다.

| HTTP 상태 | 용도 |
|---|---|
| 400 | JSON 형식 오류, 유효성 오류, 지원하지 않는 필드·값 |
| 401 | 인증 누락, 만료 또는 무효 토큰, 로그인 실패 |
| 403 | 인증은 유효하지만 역할 또는 계정 상태상 작업할 수 없음 |
| 404 | 리소스가 없거나 요청자가 조회할 수 없음 |
| 409 | 현재 리소스 상태 또는 관계 제약과 충돌 |
| 413 | 미디어 파일 크기 또는 디코딩 후 픽셀 한도 초과 |
| 415 | 허용되지 않는 요청 미디어 형식 |
| 429 | 보안 정책의 요청 제한을 초과함 |
| 500 | 예기치 않은 서버 오류 |
| 502 | 외부 인증 공급자가 요청을 처리하지 못함 |
| 503 | 미디어 저장소 비활성화 또는 저장소 작업 실패 |

대표 오류 code:

| code | HTTP | 의미 |
|---|---:|---|
| VALIDATION_ERROR | 400 | 필수값, 형식 또는 길이 검증 실패 |
| INVALID_REQUEST | 400 | 요청 본문 또는 조합이 잘못됨 |
| AUTHENTICATION_REQUIRED | 401 | 로그인 필요 |
| INVALID_CREDENTIALS | 401 | 로그인 정보가 유효하지 않음 |
| INVALID_OAUTH_STATE | 401 | OAuth 로그인 state가 누락·만료·불일치함 |
| INVALID_TOKEN | 401 | 토큰이 유효하지 않거나 만료됨 |
| ACCOUNT_SUSPENDED | 403 | 정지된 USER 계정 |
| ACCOUNT_WITHDRAWAL_PENDING | 403 | 탈퇴 대기 중인 USER 계정 |
| INSUFFICIENT_ROLE | 403 | 필요한 역할이 없음 |
| RESOURCE_NOT_FOUND | 404 | 없음 또는 권한 없는 리소스 |
| CONTENT_NOT_PUBLIC | 404 | 공개 조회 조건을 만족하지 않음 |
| DUPLICATE_RESOURCE | 409 | 고유 제약 또는 중복 생성 충돌 |
| INVALID_STATE_TRANSITION | 409 | 허용되지 않는 상태 변경 |
| WITHDRAWAL_PENDING | 409 | 로그인하려는 회원의 탈퇴 예약이 진행 중 |
| WITHDRAWAL_NOT_PENDING | 409 | 취소할 탈퇴 예약이 없음 |
| WITHDRAWAL_WINDOW_EXPIRED | 409 | 탈퇴 취소 가능 기간이 지남 |
| RESOURCE_HAS_CHILDREN | 409 | 하위 Board가 남아 있어 삭제할 수 없음 |
| CONTENT_DELETED | 409 | 삭제된 콘텐츠는 변경·복원할 수 없음 |
| THUMBNAIL_ALREADY_EXISTS | 409 | Project 대표 이미지가 이미 지정됨 |
| INVALID_MEDIA | 400 | 이미지 검증 또는 WebP 변환 실패 |
| MEDIA_TOO_LARGE | 413 | 미디어 크기 또는 픽셀 한도 초과 |
| UNSUPPORTED_MEDIA_TYPE | 415 | 지원하지 않는 실제 이미지 형식 |
| STORAGE_UNAVAILABLE | 503 | 미디어 저장소 비활성화 또는 작업 실패 |
| RATE_LIMITED | 429 | 요청 제한 초과 |
| OAUTH_PROVIDER_UNAVAILABLE | 502 | OAuth 공급자 오류 또는 응답 오류 |

비공개, 차단 또는 삭제된 콘텐츠의 존재 여부를 일반 사용자에게 노출하지 않는다. 권한이 없는 사용자의 리소스 조회는 403 대신 404를 반환한다.

## 3. 인증, 역할과 공개 판정

### 3.1 역할

| 역할 | 허용 범위 |
|---|---|
| Guest | 공개 가능한 콘텐츠와 공개 댓글 조회 |
| USER | 활성 회원의 콘텐츠·Board 관리, 댓글 및 좋아요 |
| MANAGER | 사용자 콘텐츠 검수·차단·강제 삭제, 댓글 관리, 회원 상태 및 분류 관리 |
| MASTER | MANAGER 기능과 MANAGER 계정 관리 |

MANAGER와 MASTER는 USER 상호작용을 수행하지 않는다. 관리자는 댓글을 작성하거나 좋아요를 등록·취소할 수 없다. 일반 회원 API 토큰과 관리자 토큰은 별도 계정 체계로 발급한다.

ACTIVE USER만 회원 전용 쓰기 API를 호출할 수 있다. SUSPENDED와 WITHDRAWAL_PENDING 계정은 쓰기와 상호작용이 거부된다. 탈퇴 예약 후 로그인과 기존 Refresh Token 사용을 즉시 차단한다. 탈퇴 대기 중인 회원의 Post, Project, Board, 댓글, 좋아요와 집계는 일반 사용자에게 즉시 숨긴다.

### 3.2 공개 상태와 관리자 차단

Post와 Project는 작성자가 관리하는 visibilityStatus와 관리자가 관리하는 isBlocked를 독립적으로 가진다.

일반 공개 조건은 아래 두 항목을 모두 만족하는 것이다.

- visibilityStatus가 PUBLIC이다.
- isBlocked가 false이다.

따라서 다음 규칙을 모든 목록, 상세, 검색, Board 게시글, 댓글·좋아요, 집계 및 미디어 URL 발급에 적용한다.

- isBlocked가 true이면 visibilityStatus 값과 관계없이 Guest와 일반 USER에게 노출하지 않는다.
- 작성 회원의 상태가 WITHDRAWAL_PENDING이면 탈퇴 예약 취소 가능 기간 중에도 해당 회원의 콘텐츠·댓글·좋아요·미디어를 일반 사용자에게 노출하지 않는다.
- 작성자는 자신의 콘텐츠 visibilityStatus를 PUBLIC 또는 HIDDEN으로 변경할 수 있지만 isBlocked는 변경할 수 없다.
- MANAGER와 MASTER만 isBlocked를 설정하거나 해제할 수 있다.
- 관리자가 차단을 해제해도 visibilityStatus는 바뀌지 않는다. HIDDEN 콘텐츠는 차단 해제 후에도 공개되지 않는다.
- 관리자 차단은 댓글·좋아요를 새로 만들 수 없게 하고 기존 댓글·좋아요의 일반 노출과 집계도 막는다.
- 소유자는 자신의 HIDDEN 또는 차단된 콘텐츠를 관리 목적으로 확인할 수 있다. 관리자는 모든 상태의 콘텐츠를 운영 목적으로 확인할 수 있다.
- DELETED 콘텐츠는 작성자와 일반 사용자에게 반환하지 않는다. 관리자는 검수 목록과 상세에서 확인할 수 있지만 복원할 수 없다.

### 3.3 인증 헤더

보호된 USER API는 다음 헤더를 사용한다.

~~~http
Authorization: Bearer {accessToken}
~~~

보호된 관리자 API는 관리자 로그인으로 발급된 Bearer 토큰을 사용한다. USER 토큰은 /admin 경로에 사용할 수 없고, 관리자 토큰은 USER 전용 상호작용에 사용할 수 없다.

USER Access JWT는 로그아웃 뒤에도 만료 시각까지 서명 검증을 통과할 수 있다. USER 로그아웃은 Refresh Token 세션을 폐기하고 클라이언트는 보관 중인 Access Token을 삭제한다. USER 요청마다 Access Token 블랙리스트나 세션 상태를 조회하지 않는다. SUSPENDED 또는 WITHDRAWAL_PENDING 회원의 보호된 쓰기·상호작용은 계정 상태에 따라 거부한다. 탈퇴 대기 중 발급된 기존 Access JWT도 사용자 계정 상태 검증에서 거부한다.

관리자 Access JWT는 `sid`를 포함하며, 관리자 주체의 인증 요청마다 서버가 Redis에서 활성 세션인지 확인한다. 관리자 로그아웃과 계정 비활성화는 세션을 폐기하여 해당 세션의 Access JWT를 다음 요청부터 거부한다.

## 4. 페이징과 정렬

페이지 단위 목록은 다음 query parameter를 사용한다.

| parameter | 기본값 | 규칙 |
|---|---:|---|
| page | 0 | 0부터 시작하는 페이지 번호 |
| size | 20 | 1~100 |
| sort | endpoint별 기본 정렬 | field,direction 형식. direction은 asc 또는 desc |

페이지 응답의 data는 Page 객체다.

~~~json
{
  "data": {
    "content": [],
    "page": 0,
    "size": 20,
    "totalElements": 0,
    "totalPages": 0,
    "hasNext": false,
    "hasPrevious": false
  }
}
~~~

- 범위를 벗어난 page는 200과 빈 content를 반환한다.
- 각 endpoint는 허용된 sort 필드만 받는다. 지원하지 않는 정렬 필드는 400이다.
- 정렬 키가 같은 경우 ID를 보조 정렬 키로 사용해 결과 순서를 안정화한다.
- 전체 Post·Project 목록은 publishedAt 또는 createdAt, 회원·관리자 관리 목록은 endpoint 계약의 정렬 필드, 댓글은 createdAt 정렬을 허용한다. Category·Tag·Board와 Project 하위 항목은 displayOrder 정렬을 사용한다.
- 공개 Post·Project 목록 및 검색은 기본적으로 publishedAt 내림차순, ID 내림차순이다.
- Post 블로그 목록과 본인 관리 목록은 기본적으로 displayOrder 오름차순, ID 오름차순이다. Post 목록 sort는 전체 목록에서 publishedAt·createdAt, 블로그·본인 목록에서 displayOrder·publishedAt·createdAt을 허용한다.
- 회원의 그 밖의 관리 목록은 기본적으로 createdAt 내림차순, ID 내림차순이다.
- 댓글은 createdAt 오름차순, ID 오름차순이다.
- Board, Category, Tag, Project 기능·링크·미디어는 displayOrder 오름차순이다.
- 페이징은 권한 및 공개 상태 필터를 적용한 뒤 수행한다. 권한 없는 SECRET 댓글을 페이지 수 계산에 포함하지 않는다.
## 5. API 목록

### 5.1 인증과 회원

| Method | Path | 접근 | 설명 |
|---|---|---|---|
| POST | /api/v1/auth/naver/authorization | Guest | Naver 로그인 URL과 일회용 state 발급 |
| POST | /api/v1/auth/naver/login | Guest | Naver authorization code와 state로 USER 로그인·가입 |
| POST | /api/v1/auth/naver/withdrawal/cancel | Guest | Naver 재인증으로 7일 이내 탈퇴 예약 취소 |
| POST | /api/v1/auth/token/refresh | Refresh Cookie | USER Access Token 갱신 및 Refresh Cookie 회전 |
| POST | /api/v1/auth/logout | Refresh Cookie | USER Refresh Token 세션 폐기 및 쿠키 삭제 |
| POST | /api/v1/admin/auth/login | Guest | 관리자 로그인 |
| POST | /api/v1/admin/auth/token/refresh | Admin Refresh Cookie | 관리자 Access Token 갱신 및 Refresh Cookie 회전 |
| POST | /api/v1/admin/auth/logout | Admin Refresh Cookie | 관리자 Refresh Token·`sid` 세션 폐기 및 쿠키 삭제 |
| GET | /api/v1/members/me | USER | 내 계정·프로필 설정 상태 조회 |
| POST | /api/v1/members/me/profile | USER | 블로그명·고정 공개 아이디 최초 설정 |
| PATCH | /api/v1/members/me/profile | USER | 블로그명·닉네임 변경, 필드별 7일 쿨타임 |
| DELETE | /api/v1/members/me | USER | 회원 탈퇴 |
| GET | /api/v1/members/me/posts | USER | 내 Post 목록, 공개 상태 필터 가능 |
| GET | /api/v1/members/me/projects | USER | 내 Project 목록, 공개 상태 필터 가능 |
| GET | /api/v1/members/{memberId}/posts | Guest | 회원의 공개 Post 목록 |
| GET | /api/v1/members/{memberId}/projects | Guest | 회원의 공개 Project 목록 |
| GET | /api/v1/members/{memberId}/boards | Guest | 공개 블로그 Board 구조 |
| GET | /api/v1/members/{memberId}/boards/{boardId}/posts | Guest | 해당 Board의 공개 Post 목록 |

### 5.2 Post

| Method | Path | 접근 | 설명 |
|---|---|---|---|
| GET | /api/v1/posts | Guest | 공개 Post 목록 |
| GET | /api/v1/posts/search | Guest | 공개 Post 기본 문자열 검색 |
| GET | /api/v1/posts/{postId} | Guest, 작성자 | 내부 Post ID로 공개 또는 본인 Post 조회 |
| GET | /api/v1/blogs/{handle}/posts | Guest | 공개 블로그 Post 목록 |
| GET | /api/v1/blogs/{handle}/posts/{postKey} | Guest, 작성자 | 공개 주소 키로 공개 또는 본인 Post 조회 |
| POST | /api/v1/posts | ACTIVE, 프로필 완료 USER | Post 생성 |
| PATCH | /api/v1/posts/{postId} | ACTIVE, 프로필 완료 작성자 | 본인 Post 수정 및 순서 이동 |
| DELETE | /api/v1/posts/{postId} | ACTIVE, 프로필 완료 작성자 | 본인 Post 논리 삭제 및 순서 압축 |
| PUT | /api/v1/posts/{postId}/thumbnail | 작성자 | 썸네일 업로드·교체 |
| DELETE | /api/v1/posts/{postId}/thumbnail | 작성자 | 썸네일 제거 |

Board 관리와 Post의 Board·Project 지정, 공개 Post 검색, 좋아요를 제공한다. 관리자 Post 운영은 6.5절을 따르며 썸네일·미디어는 6.8절을 따른다.

### 5.3 Project

| Method | Path | 접근 | 설명 |
|---|---|---|---|
| GET | /api/v1/projects | Guest | 공개 Project 목록 |
| GET | /api/v1/projects/search | Guest | 공개 Project 검색 |
| GET | /api/v1/projects/{projectId}/posts | Guest | 공개 Project에 직접 연결된 공개 Post 목록 |
| GET | /api/v1/projects/{projectId} | Guest, 작성자, MANAGER, MASTER | 공개 Project 또는 권한 있는 상세 조회 |
| POST | /api/v1/projects | ACTIVE, 프로필 완료 USER | Project 생성 |
| PATCH | /api/v1/projects/{projectId} | ACTIVE, 프로필 완료 작성자 | 본인 Project 수정 |
| DELETE | /api/v1/projects/{projectId} | ACTIVE, 프로필 완료 작성자 | 본인 Project 논리 삭제 |
| POST | /api/v1/projects/{projectId}/media | 작성자 | 대표 이미지 또는 스크린샷 추가 |
| PATCH | /api/v1/projects/{projectId}/media/{mediaId} | 작성자 | 미디어 설명·순서 수정 |
| DELETE | /api/v1/projects/{projectId}/media/{mediaId} | 작성자 | Project 미디어 제거 |

Project 기본 CRUD·공개 목록·상세·검색, 회원별 공개 목록과 본인 목록, Post 연결을 제공한다. 이미지·미디어 API는 6.8절을 따른다.

### 5.4 Board

| Method | Path | 접근 | 설명 |
|---|---|---|---|
| GET | /api/v1/members/me/boards | ACTIVE USER | 내 Board 구조 |
| GET | /api/v1/members/{memberId}/boards | Guest | 공개 블로그 Board 구조 |
| GET | /api/v1/members/{memberId}/boards/{boardId}/posts | Guest | 해당 Board에 직접 배치한 공개 Post 목록 |
| POST | /api/v1/boards | ACTIVE USER | Board 생성 |
| PATCH | /api/v1/boards/{boardId} | ACTIVE 소유자 | 이름, 부모, 순서 수정 |
| DELETE | /api/v1/boards/{boardId} | ACTIVE 소유자 | Board 삭제. 연결 Post는 미분류 처리 |

Board 쓰기는 프로필 설정 완료를 요구하지 않는다. POST는 name을 필수로 받고 parentId는 생략 또는 null이면 루트, displayOrder는 생략 시 0이다. PATCH에서 생략 필드는 보존하고 parentId에 null을 지정하면 루트로 이동한다. Board 구조 조회는 query parameter를 받지 않는다. Board별 Post 목록은 page(기본 0), size(기본 20, 최대 100), sort를 지원하며 기본 정렬은 displayOrder 오름차순, 같은 순서는 숫자 ID 오름차순이다. 이 목록은 지정 Board에 직접 배치한 Post만 포함하며 하위 Board의 Post를 합치지 않고 categoryId·tagId 필터도 지원하지 않는다. 전역 Post 목록의 boardId 필터는 제공하지 않는다.

### 5.5 Category와 Tag

| Method | Path | 접근 | 설명 |
|---|---|---|---|
| GET | /api/v1/categories | Guest | 공개 탐색용 Category 트리 |
| GET | /api/v1/tags | Guest | 공개 탐색용 Tag 목록 |
| POST | /api/v1/tags | USER | 자유 태그 생성 또는 기존 ACTIVE 태그 재사용 |
| GET | /api/v1/admin/categories | MANAGER, MASTER | 전체 Category 목록 |
| POST | /api/v1/admin/categories | MANAGER, MASTER | Category 생성 |
| PATCH | /api/v1/admin/categories/{categoryId} | MANAGER, MASTER | 이름, slug, 부모, 순서, 상태 수정 |
| GET | /api/v1/admin/tags | MANAGER, MASTER | 전체 Tag 목록 |
| POST | /api/v1/admin/tags | MANAGER, MASTER | Tag 생성 |
| PATCH | /api/v1/admin/tags/{tagId} | MANAGER, MASTER | 이름, slug, 순서, 상태 수정 |

참조 중인 Category와 Tag는 물리 삭제하지 않는다. 관리자 API는 status를 INACTIVE로 바꾼다. 비활성 항목은 신규 지정에서 제외하지만 기존 공개 콘텐츠의 표시·탐색에는 남을 수 있다. 공용 GET은 활성 항목과 공개 콘텐츠에서 사용 중인 비활성 항목을 반환하며 응답에 status를 표시한다.

### 5.6 댓글과 좋아요

대상별 FK와 접근 규칙을 분명히 하기 위해 Post와 Project 하위 경로를 각각 사용한다.

| Method | Path | 접근 | 설명 |
|---|---|---|---|
| GET | /api/v1/posts/{postId}/comments | Guest, USER, 작성자, MANAGER, MASTER | 댓글 목록. SECRET 접근 규칙 적용 |
| POST | /api/v1/posts/{postId}/comments | USER | 댓글 생성 |
| GET | /api/v1/posts/{postId}/comments/{commentId} | Guest(PUBLIC), 댓글 작성자, 콘텐츠 작성자 | 권한이 있는 단일 댓글 조회 |
| PATCH | /api/v1/posts/{postId}/comments/{commentId} | 댓글 작성자 | 본인 댓글 수정 |
| DELETE | /api/v1/posts/{postId}/comments/{commentId} | 댓글 작성자 | 본인 댓글 논리 삭제 |
| GET | /api/v1/projects/{projectId}/comments | Guest, USER, 작성자, MANAGER, MASTER | 댓글 목록. SECRET 접근 규칙 적용 |
| POST | /api/v1/projects/{projectId}/comments | USER | 댓글 생성 |
| GET | /api/v1/projects/{projectId}/comments/{commentId} | Guest(PUBLIC), 댓글 작성자, 콘텐츠 작성자 | 권한이 있는 단일 댓글 조회 |
| PATCH | /api/v1/projects/{projectId}/comments/{commentId} | 댓글 작성자 | 본인 댓글 수정 |
| DELETE | /api/v1/projects/{projectId}/comments/{commentId} | 댓글 작성자 | 본인 댓글 논리 삭제 |
| PUT | /api/v1/posts/{postId}/like | USER | Post 좋아요 |
| DELETE | /api/v1/posts/{postId}/like | USER | Post 좋아요 취소 |
| PUT | /api/v1/projects/{projectId}/like | USER | Project 좋아요 |
| DELETE | /api/v1/projects/{projectId}/like | USER | Project 좋아요 취소 |
| GET | /api/v1/admin/post-comments | MANAGER, MASTER | Post 댓글 운영 목록 |
| DELETE | /api/v1/admin/post-comments/{commentId} | MANAGER, MASTER | Post 댓글 운영 삭제 |
| GET | /api/v1/admin/project-comments | MANAGER, MASTER | Project 댓글 운영 목록 |
| DELETE | /api/v1/admin/project-comments/{commentId} | MANAGER, MASTER | Project 댓글 운영 삭제 |

PUT like는 이미 좋아요 상태여도 성공하는 멱등 동작이다. DELETE like는 이미 취소된 경우에도 성공한다. 두 응답은 204다.

### 5.7 관리자 운영

| Method | Path | 접근 | 설명 |
|---|---|---|---|
| GET | /api/v1/admin/posts | MANAGER, MASTER | 전체 Post 검수 목록 |
| GET | /api/v1/admin/posts/{postId} | MANAGER, MASTER | 모든 상태의 Post 검수 상세 |
| PUT | /api/v1/admin/posts/{postId}/block | MANAGER, MASTER | Post 차단 |
| DELETE | /api/v1/admin/posts/{postId}/block | MANAGER, MASTER | Post 차단 해제 |
| DELETE | /api/v1/admin/posts/{postId} | MANAGER, MASTER | Post 강제 삭제 |
| GET | /api/v1/admin/projects | MANAGER, MASTER | 전체 Project 검수 목록 |
| GET | /api/v1/admin/projects/{projectId} | MANAGER, MASTER | 모든 상태의 Project 검수 상세 |
| PUT | /api/v1/admin/projects/{projectId}/block | MANAGER, MASTER | Project 차단 |
| DELETE | /api/v1/admin/projects/{projectId}/block | MANAGER, MASTER | Project 차단 해제 |
| DELETE | /api/v1/admin/projects/{projectId} | MANAGER, MASTER | Project 강제 삭제 |
| GET | /api/v1/admin/members | MANAGER, MASTER | 회원 목록 |
| GET | /api/v1/admin/members/{memberId} | MANAGER, MASTER | 회원 상태 상세 |
| PATCH | /api/v1/admin/members/{memberId}/status | MANAGER, MASTER | ACTIVE/SUSPENDED 전환 |
| GET | /api/v1/admin/admin-accounts | MASTER | 관리자 계정 목록 |
| POST | /api/v1/admin/admin-accounts | MASTER | MANAGER 계정 생성 |
| PATCH | /api/v1/admin/admin-accounts/{adminId}/status | MASTER | MANAGER 계정 활성화·비활성화 |

MVP에는 관리자의 콘텐츠 작성자 변경, DELETED 콘텐츠 복원, 신고 처리, 관리자 권한 세분화 API가 없다.
## 6. 리소스 계약

### 6.1 USER 인증

Naver 로그인 시작 요청은 본문 없이 `POST /api/v1/auth/naver/authorization`을 호출한다. 응답은 `authorizationUrl`을 반환하고, 서버는 5분 유효한 일회용 `naver_oauth_state` HttpOnly 쿠키를 설정한다. 클라이언트는 URL로 이동한 뒤 callback에서 받은 code와 state를 로그인 요청에 보낸다.

로그인 시작과 로그인 요청 모두 브라우저 쿠키를 포함해야 한다. 같은 사이트 내에서 출처가 다른 프런트엔드를 사용하는 경우 `credentials: include`로 요청하고, API의 허용 CORS Origin을 정확히 설정한다.

Naver 로그인 요청:

~~~json
{
  "authorizationCode": "Naver에서 반환된 일회용 인증 코드",
  "state": "Naver에서 반환된 일회용 state 값"
}
~~~

서버는 요청의 state, `naver_oauth_state` 쿠키, Redis에 보관된 일회용 state를 대조하고 성공적으로 확인한 state를 한 번만 소비한다. 검증 실패 시 Naver token API를 호출하지 않는다.

최초 가입 시 Naver 닉네임과 프로필 이미지로 회원을 초기화한다. 선택 정보인 닉네임이 없으면 `pebble`을 기본값으로 사용하고, 프로필 이미지가 없으면 NULL로 저장한다. 재로그인에서는 저장된 회원 프로필을 유지한다.

성공 응답 data:

~~~json
{
  "data": {
    "accessToken": "access-token",
    "tokenType": "Bearer",
    "accessTokenExpiresIn": 900,
    "member": {
      "id": "721389012345678901",
      "nickname": "pebble",
      "profileImageUrl": null,
      "status": "ACTIVE",
      "role": "USER",
      "blogName": null,
      "handle": null,
      "profileCompleted": false
    }
  }
}
~~~

로그인 성공 시 Refresh Token은 JSON 응답에 넣지 않고 `HttpOnly; Secure; SameSite=Lax` 쿠키의 `Set-Cookie` 헤더로 전달한다. USER Refresh Token은 비활성 14일, 최초 로그인부터 절대 30일 동안 유효하다. 브라우저는 이후 refresh와 logout 요청에 쿠키를 자동으로 포함한다. refresh 요청은 JSON 본문 없이 쿠키로 인증하고, 성공 시 회전된 Refresh Token을 쿠키로 다시 설정하며 새 Access Token은 응답 본문으로 반환한다. 쿠키 `Max-Age`는 비활성 만료와 남은 절대 만료 기간 중 짧은 쪽으로 설정한다. 로그아웃은 Refresh Token 세션을 서버에서 폐기하고 쿠키를 만료시킨 뒤 204를 반환한다.

`POST /api/v1/auth/token/refresh`와 `POST /api/v1/auth/logout`에는 정확한 허용 프런트엔드 `Origin` 헤더가 필수다. 누락, `null`, 중복 또는 허용 목록 밖 Origin은 상태 변경 없이 403 `INSUFFICIENT_ROLE` 공통 오류로 거부한다. 브라우저는 헤더를 자동으로 보내고, API 클라이언트 테스트는 이를 명시해야 한다. CORS는 이 두 경로에도 POST와 `Content-Type`, `Authorization`, `Accept` 헤더 및 Credential을 정확한 Origin에만 허용한다.

Refresh 성공 응답 data는 `{accessToken, tokenType: "Bearer", accessTokenExpiresIn: 900}`이다. Refresh Token 원문은 응답 본문에 포함하지 않는다. 쿠키 누락·변조·만료·폐기·재사용은 401 `INVALID_REFRESH_TOKEN`이다. SUSPENDED는 403 `ACCOUNT_SUSPENDED`, WITHDRAWAL_PENDING은 409 `WITHDRAWAL_PENDING`으로 거부하고 제출된 Family를 폐기한다. 회원이 사라진 경우도 Family를 폐기하고 401로 거부한다. Redis 장애는 갱신·폐기를 성공으로 간주하지 않으며 공통 500 오류를 반환한다.

기존 토큰의 재사용은 새 토큰을 포함한 Family 전체를 폐기한다. 동일 세션의 Refresh를 탭 간에도 단일화하고, 응답 유실 후 이전 쿠키로 재시도하면 재로그인이 필요하다. 로그아웃은 소비된 이전 토큰으로도 Family를 폐기한다. 쿠키가 없거나 알 수 없거나 이미 만료·폐기된 경우에도 로그아웃은 멱등적으로 쿠키를 삭제하고 204를 반환한다. Origin 방어는 이러한 요청에도 동일하게 적용한다. Refresh 오류 응답은 회전 쿠키를 발급하지 않는다.

클라이언트는 Access Token을 메모리에 보관하고 보호된 요청에 `Authorization: Bearer`로 전달한다. USER 로그아웃 뒤 이미 발급된 USER Access JWT는 만료 시각까지 서버에서 서명 검증을 통과할 수 있다. USER Access JWT의 즉시 블랙리스트 확인은 하지 않는다. USER와 관리자 Access JWT의 수명은 발급 시점부터 900초(15분)이며 응답의 `accessTokenExpiresIn`은 초 단위로 `900`을 반환한다.

### 6.2 관리자 인증과 회원

현재 관리자 login·refresh·logout, MASTER의 관리자 계정 목록·MANAGER 생성·상태 설정, MANAGER/MASTER의 회원 목록·상세·상태 설정을 제공한다. Post/Project 검수·차단·해제·강제 삭제와 관리자 댓글 조회·삭제와 Category·Tag 전체 조회·생성·수정도 제공한다. 로그인은 200과 `{accessToken, tokenType: "Bearer", accessTokenExpiresIn: 900, admin}`을 반환한다. loginId는 영문·숫자로 시작하는 영문·숫자·점·밑줄·하이픈 3~100자로 대소문자를 구분한다. password는 빈 값·제어 문자·잘못된 Unicode를 거부하고 최대 128 코드 포인트를 허용한다. 로그인 JSON은 두 필드만 허용하며 중복 필드·추가 JSON 값을 거부한다. 미존재·잘못된 비밀번호·INACTIVE는 모두 `INVALID_CREDENTIALS` 401이다.

관리자 쿠키는 `admin_refresh_token`, Path는 `/api/v1/admin/auth`이며 USER 쿠키와 상호 교환할 수 없다. 세 POST 모두 허용 Origin 하나를 필수로 요구하며 누락·null·미허용·중복 Origin은 403이다. query는 허용하지 않으며 refresh·logout은 요청 본문도 금지한다. 만료·재사용·폐기·계정 상태/role 불일치 refresh는 `INVALID_REFRESH_TOKEN` 401이고 재사용은 sid도 폐기한다. 로그아웃은 미확인·없는 쿠키에도 204와 쿠키 만료를 반환한다. 제한 초과는 `RATE_LIMITED` 429다. 관리자 Bearer 요청은 공개 경로에서도 현재 DB 계정 상태·role과 Redis sid를 검증하며 무효이면 `INVALID_TOKEN` 401이다. 관리자에게 USER 전용 권한을 부여하지 않는다.

관리자 로그인 요청은 `{loginId, password}`를 받는다. 성공 응답은 Access Token과 `admin: {id, loginId, role, status}`를 data에 반환하고 관리자 Refresh Token은 별도의 `HttpOnly; Secure; SameSite=Lax` 쿠키로 설정한다. 관리자(MANAGER, MASTER) Refresh Token은 비활성 1시간, 최초 로그인부터 절대 24시간 동안 유효하다. 관리자 Access JWT의 만료 시간도 900초(15분)다. 관리자 refresh와 로그아웃은 관리자 Refresh Cookie를 사용하며 JSON 본문으로 Refresh Token을 받지 않는다. 회전 시 쿠키 `Max-Age`는 비활성 만료와 남은 절대 만료 기간 중 짧은 쪽으로 갱신한다. 관리자 로그아웃은 해당 Refresh Token Family와 Redis의 `sid`를 폐기하고 쿠키를 만료시키므로 그 세션의 Access JWT는 다음 관리자 인증 요청부터 거부한다. 관리자 계정 생성은 `{loginId, initialPassword}`를 받고 새 계정 role은 MANAGER로 고정한다. MASTER 계정은 이 API로 생성하지 않는다.

관리자 회원 상태 변경 요청은 `{status}`를 받으며 값은 ACTIVE 또는 SUSPENDED다. `WITHDRAWAL_PENDING` 전환과 취소는 회원 탈퇴 API만 수행한다. 탈퇴 유예 기간이 끝나면 회원 레코드를 물리 삭제하므로 `WITHDRAWN` 상태는 저장하지 않는다. 관리자 계정 상태 변경은 `{status}`를 받으며 ACTIVE 또는 INACTIVE다.

관리자 계정 관리는 검증된 MASTER Bearer 및 현재 DB ACTIVE·MASTER 상태를 요구한다. Refresh Cookie만으로 인증하지 않는다. `POST /admin/admin-accounts`는 `{loginId, initialPassword}`만 받으며 비밀번호는 12~128 유니코드 코드 포인트, 공백 전용·제어 문자·잘못된 Unicode는 금지다. 대소문자 구분 loginId 고유 중복은 `DUPLICATE_RESOURCE` 409다. 생성 계정은 ACTIVE MANAGER로 고정하며 201, 생성 URI의 Location과 `{id, loginId, role, status, createdAt, updatedAt}`을 반환한다. 비밀번호·hash를 응답하지 않는다.

`GET /admin/admin-accounts`는 MASTER/MANAGER와 ACTIVE/INACTIVE 전체 계정을 같은 안전한 DTO의 공통 페이지 응답으로 반환한다. page=0, size=20(최대 100), 기본 createdAt desc 및 ID desc이며 createdAt·updatedAt·loginId의 asc/desc를 허용하고 같은 방향의 ID를 보조 정렬한다. 지원 필터는 없으며 알 수 없거나 중복된 query·본문·범위 초과 값은 400이다. 허용된 Origin에 GET/POST, 숫자 ID status 경로에 PATCH CORS를 등록한다.

`PATCH /admin/admin-accounts/{adminId}/status`는 `{status: "ACTIVE"|"INACTIVE"}`만 받고 200과 위 DTO를 반환한다. MANAGER만 대상이며 MASTER·본인·없는 계정은 `RESOURCE_NOT_FOUND` 404다. ID는 양수 BIGINT의 10진 문자열이며 선행 0·범위 초과는 400이다. 두 쓰기는 query, 미지원/중복 JSON 필드·null·추가 JSON 값을 거부한다. 같은 상태 설정도 성공하지만 해당 계정의 기존 모든 sid를 폐기하며 갱신·Access 사용을 막는다. ACTIVE로 바꿔도 이전 세션은 복구되지 않아 새 로그인해야 한다. role·비밀번호 변경과 삭제·개별 상세 조회는 제공하지 않는다.

관리자 회원 운영은 검증된 MANAGER/MASTER Bearer 및 현재 DB ACTIVE 관리자 상태를 요구한다. `GET /admin/members`는 모든 회원 상태를 포함하며 선택적 status(ACTIVE/SUSPENDED/WITHDRAWAL_PENDING)와 q를 AND 적용한다. q는 앞뒤 공백 제거 후 1~200 유니코드 코드 포인트이며 NUL·잘못된 Unicode를 거부하고, nickname·blogName·handle에서 대소문자 구분 없는 부분 문자열을 찾는다. `%`, `_`, 역슬래시는 문자 그대로 검색한다. page=0, size=20(최대 100), 기본 createdAt desc와 ID desc이며 createdAt·updatedAt·nickname·blogName·handle의 asc/desc 및 같은 방향 ID 보조 정렬을 지원한다. 페이지 offset은 signed INT 범위 안이어야 한다.

목록은 공통 페이지 응답을 사용하며 항목은 `{id, nickname, profileImageUrl, blogName, handle, profileCompleted, status, createdAt, updatedAt, withdrawalRequestedAt, withdrawalScheduledAt}`이다. `GET /admin/members/{memberId}`와 상태 변경의 200 응답도 같은 안전한 DTO를 반환한다. OAuth 연결·공급자 subject·인증 비밀은 포함하지 않는다. ID는 양수 BIGINT의 선행 0 없는 10진 문자열이다. 잘못된 숫자 ID·미지원/중복 query·GET 본문·잘못된 페이지/정렬/필터는 400 INVALID_REQUEST이고 미존재 회원은 404다. 숫자 외 미지원 관리자 경로는 기존 기본 거부 정책을 따른다.

`PATCH /admin/members/{memberId}/status`는 `{status: "ACTIVE"|"SUSPENDED"}`만 받으며 query·중복/추가 JSON 필드·null·추가 JSON 값을 거부한다. WITHDRAWAL_PENDING 회원은 두 상태 설정 모두 WITHDRAWAL_PENDING 409이며 탈퇴 시각과 원래 상태를 보존한다. 같은 상태 설정도 성공하고 모든 기존 USER Refresh Family를 폐기하며 같은 상태인 경우 updatedAt은 바꾸지 않는다. ACTIVE 복구 뒤 기존 Family는 되살아나지 않아 새 로그인이 필요하다. USER Access JWT의 900초 만료 정책은 유지하므로 정지 동안 업무별 ACTIVE 검사로 회원 기능을 거부하고, 복구 후 만료 전 기존 Access JWT는 다시 사용할 수 있다. 정지는 기존 공개 콘텐츠·댓글·좋아요를 숨기거나 삭제하지 않는다. 허용 Origin에 정확한 목록/숫자 상세 GET·숫자 status PATCH CORS를 등록하며 쿠키 단독 인증은 제공하지 않는다.

회원 상세 응답:

`GET /members/me`는 USER Access JWT의 검증된 `sub`에서 본인 회원 ID를 식별한다. 요청으로 조회할 회원 ID를 받지 않는다. 현재 DB의 ACTIVE 회원만 아래 정보를 조회할 수 있다. 인증 누락은 `AUTHENTICATION_REQUIRED` 401, 무효·만료 토큰은 `INVALID_TOKEN` 401, 회원이 없으면 `RESOURCE_NOT_FOUND` 404, 정지 상태는 `ACCOUNT_SUSPENDED` 403, 탈퇴 대기 상태는 `ACCOUNT_WITHDRAWAL_PENDING` 403을 반환한다. Refresh Cookie만으로 이 API에 인증할 수 없다. 허용한 프런트엔드 Origin의 GET과 Authorization 헤더 preflight를 지원한다.

~~~json
{
  "data": {
    "id": "721389012345678901",
    "nickname": "pebble",
    "profileImageUrl": null,
    "status": "ACTIVE",
    "createdAt": "2026-09-30T03:00:00Z",
    "blogName": null,
    "handle": null,
    "profileCompleted": false,
    "nicknameChangeAvailableAt": null,
    "blogNameChangeAvailableAt": null
  }
}
~~~

일반 회원 최초 설정과 변경:

- 신규 Naver 가입의 닉네임은 공급자 별명(없거나 공백이면 `pebble`)을 기본값으로 한다. 이미 사용 중이면 무작위 4~6자리 숫자를 뒤에 붙여 다시 중복을 확인한다. 99회 무작위 후보 충돌 이후에는 숫자 접미사 순차 탐색으로 빈 이름을 찾는다. 접미사를 포함해 30 유니코드 문자 이내로 잘라 저장한다. 재로그인은 저장된 닉네임을 변경하지 않는다. Naver 로그인 member 응답에도 `blogName`, `handle`, `profileCompleted`를 포함한다.
- `POST /members/me/profile` 요청은 `{blogName, handle, nickname?}`이다. 200과 위 회원 응답을 반환한다. 최초 설정 전에 기본 닉네임을 직접 바꿀 수 있으며 생략하면 현재 기본값을 사용한다. `blogName`은 최대 100자, `nickname`은 최대 30자이고 공백만 있는 값은 거부한다. 앞뒤 공백을 제거하고 NFC로 정규화한다. 두 표시 이름은 한글·영문을 허용하며 정규화한 저장 값의 정확한 일치로 중복을 검사한다.
- `handle`은 Naver 식별자와 별개의 영구 공개 아이디다. 영문 소문자로 시작하고 영문 소문자·숫자·하이픈·언더바 3~30자를 사용한다. 대문자는 소문자로 정규화하고 끝 하이픈 및 예약어 `admin`, `api`, `auth`, `me`, `posts`, `search`, `settings`, `www`를 거부한다. 최초 설정 후 변경할 수 없으며 DB에서도 변경을 막는다.
- 블로그명·닉네임·handle은 서비스 전체에서 각각 고유하다. 최종 저장까지 같은 이름 공간의 PostgreSQL 트랜잭션 잠금과 고유 제약으로 조정한다. 직접 선택한 이름은 자동으로 수정하지 않고 `DUPLICATE_NICKNAME`, `DUPLICATE_BLOG_NAME`, `DUPLICATE_HANDLE` 409를 반환한다. 기본 Naver 닉네임에만 자동 접미사를 사용한다.
- 최초 설정은 한 번만 가능하고 재요청은 `PROFILE_ALREADY_COMPLETED` 409다. 로그인 직후 `profileCompleted=false`이면 최초 설정이 필요하다. 미설정 회원도 설정·본인 조회·기존 인증 API는 사용할 수 있고, 후속 콘텐츠 쓰기는 member의 완료 상태 검증을 적용한다.
- `PATCH /members/me/profile`은 `{blogName?, nickname?, removeProfileImage?}`만 허용한다. 생략은 유지하고 명시 null·비문자열·미지원 필드는 400이다. handle을 포함하면 값이 같아도 400이다. 최초 설정 전 PATCH는 `PROFILE_REQUIRED` 409다.
- 최초 설정일부터 각 표시 이름의 마지막 실제 변경 시각을 기준으로 독립적인 7일(168시간) 쿨타임을 적용한다. 같은 값 또는 빈 PATCH는 성공하며 시각을 갱신하지 않는다. 제한 중 변경은 `NICKNAME_CHANGE_COOLDOWN` 또는 `BLOG_NAME_CHANGE_COOLDOWN` 409다. 변경 가능 시각은 위 응답의 두 `*ChangeAvailableAt` UTC 필드로 확인한다. 여러 필드를 보낸 요청은 모두 성공하거나 모두 취소된다.
- 두 쓰기 API는 검증된 USER Bearer JWT와 DB ACTIVE 상태를 요구하며 같은 회원 행을 잠가 최초 설정·쿨타임을 검증한다. Refresh Cookie나 세션 쿠키로 인증하지 않는다. 허용 Origin에 POST/PATCH와 Authorization·Content-Type CORS를 제공한다. 별도 프로필 GET은 제공하지 않고 `GET /members/me`를 사용한다.

프로필 생성·편집 확장 (2026-10-05):

- `GET /members/me/profile/availability?field=blogName|nickname|handle&value=...`는 ACTIVE USER Bearer를 요구하며 `{data:{available,value}}`를 반환한다. value는 저장과 동일하게 정규화하며 본인의 현재 값은 사용 가능하다. 정확히 field·value 각 1개만 허용한다. 중복 검사는 예약이 아니며 최종 쓰기 시 고유 제약을 다시 적용한다.
- 표시 이름은 한글·영문·숫자·공백·일반 특수문자·이모지를 허용한다. 공백만 있는 값, 제어 문자, 잘못된 Unicode를 거부하며 길이는 정규화 후 유니코드 코드 포인트 수로 검사한다.
- 생성 JSON에도 선택적인 `removeProfileImage` boolean을 허용한다. true는 SNS 기본 사진 또는 업로드 사진을 제거하며 false·생략은 유지한다. null·비boolean은 거부한다.
- 사진을 함께 저장할 때 같은 POST/PATCH 경로에 multipart/form-data를 전송한다. `profile` 문자열 파라미터 1개에 위 JSON을 넣고 `file` 파일 1개를 넣는다. 추가 파일·파라미터·query·중복 JSON 필드·후행 JSON은 거부한다. 파일과 removeProfileImage=true는 함께 사용할 수 없다.
- 파일은 기존 미디어와 동일하게 PNG/JPEG/WebP 정지 이미지만 10MiB·2천만 픽셀·한 변 8천 픽셀 이내로 허용한다. 480px WebP로 재인코딩해 R2 비공개 저장소에 저장한다. 클라이언트가 URL·storage key를 지정할 수 없다.
- 이름·생성 완료 상태·사진 참조는 한 DB 트랜잭션에서 저장한다. 실패한 업로드는 사전 커밋한 삭제 작업이 회수하고, 교체·초기화·회원 물리 삭제로 제거한 사진도 삭제 큐에 등록한다. 사진에는 이름의 7일 변경 제한을 적용하지 않는다.
- 본인 조회·로그인·공개 블로그·게시글/프로젝트/댓글 작성자 응답은 저장 사진의 만료 signed URL을 반환한다. 사진을 선택하거나 초기화한 UI 초안은 저장 전 서버를 변경하지 않는다.

`DELETE /members/me`는 ACTIVE USER Bearer를 요구하고 본문·query를 받지 않는다. 회원 쓰기 잠금 안에서 모든 기존 Refresh Family를 폐기하고 `WITHDRAWAL_PENDING` 및 요청 시각·요청 시각 + 정확히 7일인 삭제 예정 시각을 기록한다. 응답은 202와 `{withdrawalScheduledAt}`이며 Refresh Cookie를 만료시킨다. 정지는 403 ACCOUNT_SUSPENDED, 이미 탈퇴 대기는 403 ACCOUNT_WITHDRAWAL_PENDING으로 거부하고 최초 예정 시각을 연장하지 않는다. 미존재 회원은 404다. 허용 Origin의 GET·DELETE·Authorization CORS를 지원한다.

탈퇴 대기 중에는 기존 Access JWT의 만료 여부와 관계없이 보호된 USER 업무 요청을 현재 회원 상태 검사로 거부하고 회원의 콘텐츠·댓글·좋아요를 일반 조회·검색·집계에서 숨긴다. 콘텐츠 자체의 상태와 상호작용 이력은 보존한다. 일반 Naver 로그인은 WITHDRAWAL_PENDING 409를 반환하며 예약을 자동 취소하지 않는다.

Naver 본인 인증과 일회용 state 검증을 마친 로그인에서 WITHDRAWAL_PENDING이면 `error.details`에 `{field: "withdrawalScheduledAt", reason: "<ISO-8601 Instant>"}` 한 항목으로 실제 삭제 예정 시각을 전달한다. 회원 ID·OAuth subject·토큰은 반환하지 않는다. Refresh 및 관리자 상태 변경 오류에는 이 정보를 추가하지 않는다. 프론트는 한국 시간으로 `YYYY-MM-DD HH:MM:SS`를 표시하고 기존 Naver 재인증 기반 예약 취소로 안내한다.

`POST /auth/naver/withdrawal/cancel`은 `{authorizationCode, state}`만 받는다. JSON 중복·추가 필드·null·후행 값·잘못된 Unicode·query는 400 INVALID_REQUEST다. code/state는 공백만인 값을 거부하고 길이는 각각 4096/256 UTF-16 단위 이하다. 로그인과 동일한 일회용 state 및 HttpOnly state Cookie를 검증하며 허용 목록의 단일 Origin이 필수다. 누락·null·중복·불허 Origin은 403이다. Naver 재인증 후 기존 OAuth 연결의 회원만 취소하며 신규 계정을 생성하지 않는다. 외부 공급자 통신은 DB 트랜잭션 밖에서 수행하고 회원 쓰기 잠금 뒤 현재 상태와 기한을 다시 확인한다. ACTIVE/SUSPENDED는 409 WITHDRAWAL_NOT_PENDING, 예정 시각 이상은 409 WITHDRAWAL_EXPIRED, 미존재 연결/회원은 404다.

취소 성공은 200과 `{ "data": { "status": "ACTIVE" } }`를 반환하고 예약 시각을 NULL로 되돌린다. 모든 기존 Refresh Family를 다시 폐기하고 state·Refresh Cookie를 만료시키며 새 토큰을 발급하지 않는다. Refresh 세션을 복구하지 않아 다시 로그인해야 한다. 기존 USER Access JWT는 요청별 Redis 조회를 하지 않는 900초 정책을 유지하므로 ACTIVE 복구 후 만료 전 토큰은 다시 사용할 수 있다.

예정 시각 이상인 예약은 백그라운드 작업이 회원별 독립 트랜잭션으로 DB에서 물리 삭제한다. 현재 구현은 회원·OAuth 연결·Post/Project 및 하위 데이터·Board·댓글·좋아요를 제거한다. Post 썸네일·Project 미디어의 저장 참조도 함께 제거하며 별도 삭제 큐가 R2 객체 삭제를 재시도한다. DB 파기와 R2 삭제 완료 시점은 다르며 백업 복원 전 탈퇴 반영은 후속 운영 범위다. 법령상 보관하는 관리자 접속기록은 회원 콘텐츠와 분리해 SECURITY.md 정책을 따른다.

### 6.3 Post

`POST /api/v1/posts`와 `PATCH /api/v1/posts/{postId}`는 JSON 요청을 받는다. 생성은 201 Created와 블로그 상세 주소를 가리키는 `Location` 헤더를 반환한다. PATCH는 200 OK, 논리 삭제는 204 No Content를 반환한다.

~~~json
{
  "title": "Spring Security 설정",
  "summary": "인증 필터 체인을 구성한 기록",
  "blocks": [
    {
      "type": "TEXT",
      "content": "인증 처리 흐름을 설명합니다."
    },
    {
      "type": "CODE",
      "content": "http.oauth2Login();",
      "language": "JAVA",
      "title": "Security 설정"
    }
  ],
  "categoryId": "721389012345678901",
  "tagIds": ["721389012345678902", "721389012345678903"],
  "visibilityStatus": "PUBLIC",
  "slug": "spring-security-setup"
}
~~~

- title은 필수, 최대 200자다. summary는 nullable이며 최대 500자다.
- blocks는 순서 있는 배열이다. 최소 한 개를 보낸다. type은 TEXT, CODE, TABLE 또는 ARCHITECTURE이다.
- CODE 블록은 language가 필수이며 title은 선택이다. DB 제한은 content 최대 50,000자, title 최대 100자, language 최대 50자다.
- TABLE은 `language`를 생략하거나 null로 보내며 `content`는 테이블 명세서 JSON을 직렬화한 문자열이다. `schemaVersion`은 정수 1, `tableName`은 1~100자 비공백 문자열, `description`은 선택 문자열/null(최대 500자), `columns`는 1~50개 배열이다. 각 컬럼의 `name`·`dataType`은 1~100자 비공백 문자열이고 `nullable`·`primaryKey`는 필수 boolean이다. `foreignKey`는 선택 문자열/null(최대 200자, 예: member.id), 컬럼 `description`은 선택 문자열/null(최대 500자)이다. 공백 제거·대소문자 무시 기준의 컬럼명 중복과 NULL 허용 PK는 400이다. 미지 필드·중복 JSON 키·추가 JSON 문서·미지원 버전·잘못된 Unicode/NUL도 400이다. 내부 JSON 문자열을 해석한 뒤 길이·Unicode를 다시 검사하며, content 전체의 기존 50,000자 제한도 적용한다. 명세서는 실행 가능한 SQL이나 HTML이 아니다. 생성/수정은 기존 USER 소유권·상태·프로필 계약을 유지하고, 공개 조회에서는 문자열 content와 순서를 그대로 반환한다. [표현 블록 설계](POST_VISUAL_BLOCKS.md)에 예시와 단계별 범위를 기록한다.
- ARCHITECTURE는 `language` 생략/null, `content`는 JSON 문자열이다. 루트는 `schemaVersion: 1`, 필수 배열 `groups`(0~10), `nodes`(1~30), `edges`(0~60)만 허용한다. 그룹은 `{id,type,label,parentId?,bounds?}`, type은 ORACLE_CLOUD/AWS/CLOUDFLARE/DOCKER/CUSTOM이다. Docker만 기존 비-Docker 그룹(클라우드 또는 CUSTOM)을 parentId로 지정할 수 있다(최대 2단계); 최상위 Docker도 허용한다. 노드는 `{id,type,label,groupId?,icon?,position?}`, type은 CLIENT/APP/DATABASE/CACHE/STORAGE/PROXY/CUSTOM이다. 직접 만든 요소의 이름은 label로 자유롭게 입력한다. `icon`은 생략/null 또는 AWS/ORACLE_CLOUD/CLOUDFLARE/DOCKER/SPRING/POSTGRESQL/REDIS/R2/WORKERS/NGINX/NODEJS/REACT/SERVER/DATABASE/CACHE/STORAGE/CLIENT/CLOUD/CONTAINER다. 외부 아이콘 URL이나 SVG 문자열을 받지 않는다. `position`은 생략/null 또는 정확히 `{x,y}` 객체이며 각각 0~4000 정수, 캔버스 카드 좌상단 절대 좌표다. 위치가 없는 기존 version 1은 자동 배치하고 좌표를 지정한 블록은 저장한 배치를 유지한다. 그룹 `bounds`는 생략/null 또는 정확히 `{x,y,width,height}`다. x/y는 0~4000, width는 200~4200, height는 120~4200 정수이며 x+width/y+height는 4200 이하다. 없는 경계는 기존 자동 배치를 유지한다. 연결은 `{id,source,target,label?,sourceSide?,targetSide?,waypoint?}`로 기존 노드 또는 그룹 사이에 방향을 지정한다. `sourceSide`/`targetSide`는 생략/null(자동) 또는 TOP/RIGHT/BOTTOM/LEFT다. `waypoint`는 생략/null(자동) 또는 정확히 `{x,y}`이며 0~4200 정수 캔버스 좌표다. 지정한 연결 변과 경로 지점은 저장 후 공개 도식에서도 유지한다. 모든 ID는 전체 컬렉션에서 유일하고 `[a-z][a-z0-9-]{0,39}`다. groupId/parentId는 생략/null 또는 기존 그룹 ID다. 필수 label은 비공백 1~100자, 선택 연결 label은 생략/null 또는 최대 200자다. 자기 연결·같은 방향 중복 연결은 400이며 반대 방향/순환 연결은 허용한다. 미지 필드·중복 JSON 키·추가 문서·미지원 버전·잘못된 Unicode/NUL·없는 참조는 400이다. 길이는 Unicode code point 기준이며 전체 content 50,000자와 기존 회원 소유권/상태 규칙을 유지한다. 범위를 벗어난 좌표·HTML·URL·스크립트는 구조 필드로 받지 않는다; label은 실행하지 않는 텍스트다.
- HTML/MARKDOWN은 명시적인 본문 형식이다. `language`는 null/생략이어야 하고 content 50,000자·Unicode 규칙을 유지한다. 기존 TEXT는 HTML로 해석하지 않는다. HTML/Markdown은 비신뢰 저장 데이터이며 렌더러는 스크립트·이벤트·위험 URL·허용하지 않은 CSS/iframe을 제거해야 한다. HTML 코드 예시는 기존 CODE(language=HTML)로 저장한다.
- API 언어 값은 JAVA, JAVASCRIPT, TYPESCRIPT, PYTHON, HTML, CSS, SQL, JSON, YAML, MARKDOWN, BASH, SHELL이다.
- `slug`는 생성 시 선택 입력이다. 영문 소문자·숫자와 단어 사이 하이픈만 허용하며 최대 200자다. 대문자는 소문자로 바꾼다. 숫자만으로 된 값과 `search`는 사용할 수 없다. 같은 작성자가 이미 사용한 값은 `-2`, `-3` 접미사를 붙여 첫 빈 값을 할당하며 논리 삭제한 Post의 slug도 예약된 상태로 남는다. 생략하거나 null이면 slug 없이 생성되고 주소에는 postNumber를 사용한다. 기존 발행 글(`draft=false`)의 PATCH에서 `slug`를 보내면 400이다. 새 초안(`draft=true`, HIDDEN)은 `draft=false`로 확정하는 PATCH에서만 숫자 주소(null) 또는 slug를 지정할 수 있다. 확정 후에는 비공개 글도 주소를 바꿀 수 없다.
- `displayOrder`는 선택 입력이며 0 이상 정수다. 생성 기본값은 0이고 지정하면 본인의 미삭제 전체 Post 순서 안에 삽입한다. PATCH에서 지정하면 같은 목록 안에서 위치를 이동한다. 삭제하면 뒤의 Post 순서를 압축한다.
- 시스템은 작성자별 `postNumber`를 1부터 증가시키며 논리 삭제 뒤에도 재사용하지 않는다. 내부 `id`는 TSID다.
- `categoryId`는 null 또는 최하위 Category 하나다. 신규 연결은 ACTIVE Category만 허용한다. 기존 Post가 사용 중인 뒤 비활성화된 Category는 공개 조회와 분류 탐색에서 계속 연결 결과를 제공한다. 부모 Category 조회는 해당 하위 Category의 공개 Post를 포함한다.
- tagIds는 중복 없는 ID 배열이며 신규 연결은 ACTIVE Tag만 허용한다.
- `boardId`는 본인 소유의 미삭제 Board ID 문자열 또는 null이다. 생성 시 생략하면 미분류, PATCH 시 생략하면 기존 연결을 유지하고 null은 연결을 해제한다. 타인 소유·삭제·존재하지 않는 Board는 404다. `projectId`도 같은 생략·null 규칙을 따르는 본인 소유의 미삭제 Project ID 문자열이다. HIDDEN·차단 Project에도 연결할 수 있으며 타인 소유·삭제·존재하지 않는 Project는 404다. Project 공개 상태는 Post 공개 상태를 바꾸지 않고 응답에는 연결 ID만 제공한다.
- visibilityStatus는 PUBLIC 또는 HIDDEN이다. DELETED는 DELETE 동작으로만 설정한다.
- isBlocked, blockedAt, blockedByAdminId는 응답 전용이며 요청에 포함하면 400이다.
- 생성 시 title, blocks, visibilityStatus는 필수다. PATCH에서 blocks와 tagIds는 전체 교체이며, categoryId, boardId, summary는 null로 지정해 값·연결을 제거할 수 있다.
- 작성·수정·삭제에는 ACTIVE이며 최초 프로필 설정을 완료한 USER가 필요하다. 미완료 프로필은 `PROFILE_REQUIRED` 409다. 본인 글만 변경할 수 있다.
- Guest는 PUBLIC이며 차단되지 않은 Post만 조회한다. 작성자는 자신의 HIDDEN Post도 조회할 수 있으며 다른 사용자의 비공개 Post는 404다.

Post 응답은 다음 정보를 제공한다. 목록에서는 blocks와 전체 상세 필드를 생략할 수 있다.

~~~json
{
  "data": {
    "id": "721389012345678901",
    "postNumber": "12",
    "slug": "spring-security-setup",
    "urlKey": "spring-security-setup",
    "displayOrder": 0,
    "author": {
      "id": "721389012345678902",
      "handle": "pebble-dev",
      "blogName": "Pebble 개발 기록",
      "nickname": "pebble",
      "profileImageUrl": null
    },
    "title": "Spring Security 설정",
    "summary": "인증 필터 체인을 구성한 기록",
    "blocks": [
      {
        "type": "CODE",
        "content": "http.oauth2Login();",
        "language": "JAVA",
        "title": "Security 설정",
        "displayOrder": 0
      }
    ],
    "category": null,
    "tags": [],
    "boardId": null,
    "projectId": null,
    "thumbnailUrl": null,
    "visibilityStatus": "PUBLIC",
    "likeCount": 0,
    "likedByMe": false,
    "publishedAt": "2026-09-30T03:00:00Z",
    "createdAt": "2026-09-30T03:00:00Z",
    "updatedAt": "2026-09-30T03:00:00Z",
    "isBlocked": false
  }
}
~~~

`urlKey`는 slug가 있으면 slug, 없으면 `postNumber` 문자열이다. `GET /api/v1/blogs/{handle}/posts/{postKey}`에서 숫자 키는 작성자별 번호, 그 외 키는 slug로 해석한다. 본인 블로그 기본 목록은 displayOrder 오름차순이고 공개 전체 목록 기본 순서는 publishedAt 내림차순이다. 공개 주소 경로는 공개 handle과 postNumber 또는 slug를 사용하며 Naver 식별자와 내부 TSID를 경로에 사용하지 않는다. 응답의 `id`는 TSID 문자열이다.

Post 응답의 `boardId`와 `projectId`는 각각 연결 ID 문자열 또는 null이다. `thumbnailUrl`은 R2 저장소가 활성화되어 있고 썸네일이 저장되어 있으며 PUBLIC·미차단·소유자 비탈퇴 대기 조건을 만족할 때 15분 유효한 signed URL을 반환한다. 썸네일이 없거나 URL 발급 조건을 만족하지 않으면 null이다. 미디어 세부 계약은 6.8절을 따른다. `likeCount`와 `likedByMe`는 6.7절의 공개 집계·요청 회원 규칙을 따른다. 작성자 응답에는 `isBlocked`가 포함되며 Guest·다른 회원 응답에서는 생략된다. 관리자 검수 응답은 6.5절을 따른다.

### 6.4 Project

Project 생성 요청과 수정 가능한 필드:

~~~json
{
  "name": "Pebble API",
  "summary": "개발 지식과 프로젝트를 공유하는 서비스",
  "description": "서비스와 주요 구성에 대한 상세 설명",
  "architectureDescription": "Feature 단위의 백엔드 구조",
  "executionInstructions": "로컬 실행 절차",
  "lifecycleStatus": "IN_PROGRESS",
  "startedOn": "2026-01-01",
  "completedOn": null,
  "tagIds": ["721389012345678903"],
  "features": [
    {
      "title": "Naver 로그인",
      "description": "OAuth 인증으로 회원 로그인을 제공합니다."
    }
  ],
  "links": [
    {
      "linkType": "GITHUB",
      "label": "소스 코드",
      "url": "https://github.com/example/pebble",
      "displayOrder": 0
    }
  ],
  "visibilityStatus": "PUBLIC"
}
~~~

- name은 필수이며 최대 120자다.
- summary 최대 500자, description·architectureDescription 각각 최대 20,000자, executionInstructions 최대 10,000자다.
- lifecycleStatus는 IN_PROGRESS 또는 COMPLETED다. startedOn, completedOn은 YYYY-MM-DD다.
- tagIds는 중복 없는 ID 배열이며 신규 연결은 ACTIVE Tag만 허용한다.
- features 배열 항목은 title 최대 100자, description 최대 2,000자이며 요청 순서대로 저장한다.
- links의 linkType은 GITHUB, DEPLOYMENT, DOWNLOAD, OTHER다. url은 필수, 최대 2,048자이며 label은 선택, 최대 100자다. 각 항목의 displayOrder로 표시 순서를 지정한다.
- 외부 링크는 사용자 정보가 없는 절대 HTTP(S) URL만 허용한다. 상대 경로·실행 가능한 스킴·잘못된 URI는 400이다. 서버는 링크 대상에 접속하지 않는다.
- visibilityStatus는 PUBLIC 또는 HIDDEN이다. isBlocked는 작성자가 변경할 수 없다.
- PATCH에서 features, links, tagIds를 보내면 기존 전체 항목을 교체한다.
- POST에서는 name, lifecycleStatus, visibilityStatus가 필수다. 생략한 tagIds, features, links는 빈 배열로 저장한다.
- PATCH는 생략한 필드를 보존하고 nullable 문자열·날짜에 null을 보내면 값을 비운다.
- 작성·수정·삭제에는 ACTIVE이며 최초 프로필 설정을 완료한 USER가 필요하다. 본인 Project만 변경할 수 있다.
- 공개 목록은 tagId, lifecycleStatus, page, size, sort를 지원한다. 기본 정렬은 publishedAt 내림차순이며 publishedAt, createdAt의 asc 또는 desc를 허용한다.
- 공개 목록·상세는 PUBLIC·미차단·미삭제 Project만 노출한다. 작성자는 인증 후 자신의 HIDDEN·차단 Project 상세를 조회할 수 있고, DELETED Project는 조회할 수 없다.
- WITHDRAWAL_PENDING 회원의 Project는 공개 조회에서 제외한다. SUSPENDED 회원의 공개 Project는 유지하며 해당 회원의 쓰기는 거부한다.
- POST는 201과 `/api/v1/projects/{id}` Location, PATCH는 200, DELETE는 빈 204다. 타인·없는 Project의 변경은 404, 소유자의 DELETED Project 재변경은 CONTENT_DELETED 409다.
- ID는 TSID의 10진 문자열이다. 응답 owner는 id·handle·blogName·nickname·profileImageUrl을 포함한다. 작성자 상세에는 isBlocked를 포함하고 공개 목록·타인 상세에는 생략한다. 목록·상세는 기술 태그를 요청 순서로 반환하고, 주요 기능은 요청 순서, 링크는 displayOrder와 숫자 ID 오름차순으로 반환한다. 전체 교체 시 주요 기능·링크 ID는 새로 발급한다.
- 입력 문자열 길이는 유니코드 코드 포인트로 검증하며 NUL·잘못된 유니코드는 거부한다. 선택 문자열과 날짜는 null로 비울 수 있고, 배열의 null은 거부한다. 상세의 선택 description·architectureDescription·executionInstructions는 null이면 생략한다.
- 같은 이름의 Project 생성은 허용한다. 이번 API는 날짜 형식을 검증하며 기간과 lifecycleStatus를 자동으로 연동하지 않는다. 회원별 공개 목록과 본인 목록은 아직 제공하지 않는다.

Project 상세에는 미디어 배열, 주요 기능, 링크, 기술 태그와 6.7절 규칙을 따르는 likeCount·likedByMe를 포함한다. 목록 응답은 상세 설명과 주요 기능·링크·미디어 배열을 생략한다. 미디어 저장 키는 공개하지 않고, 미디어 기능은 권한을 통과한 요청에 한해 15분 만료의 signed URL을 반환한다. 만료된 URL로 아직 로드되지 않은 이미지를 요청하면 리소스 조회 API에서 새 URL을 받아 다시 요청한다. 이미 로드된 이미지는 URL 만료만으로 화면에서 사라지지 않는다.

### 6.5 관리자 차단

Post·Project 관리자 검수·차단·해제·강제 삭제는 MANAGER/MASTER Bearer 및 현재 DB ACTIVE 관리자 상태를 요구한다. 정확한 `/admin/posts`·`/admin/projects` 목록 GET, 숫자 상세 GET/DELETE, 숫자 block PUT/DELETE만 제공하며 Cookie 단독 인증·USER 권한은 허용하지 않는다. 모든 경로는 본문을 금지하며 목록 외 query는 허용하지 않는다. 미지원/중복 query·잘못된 숫자 ID·입력은 INVALID_REQUEST 400, 미존재 콘텐츠는 404다. 숫자 ID는 양수 BIGINT의 선행 0 없는 10진 문자열이고 숫자 외 미지원 관리자 경로는 기본 거부 정책을 따른다.

목록은 공개/숨김/삭제·차단 여부와 ACTIVE/SUSPENDED/WITHDRAWAL_PENDING 소유자 상태에 관계없이 조회한다. q·visibilityStatus(PUBLIC/HIDDEN/DELETED)·isBlocked(true/false)와 Post의 authorId 또는 Project의 ownerId를 AND 적용한다. q는 앞뒤 공백 제거 후 1~200 유니코드 코드 포인트이며 NUL·잘못된 Unicode를 거부한다. 검색 대상·대소문자 구분 없는 부분 문자열·특수 문자 리터럴·EXISTS 중복 방지는 각 공개 Post/Project 검색과 같고 공개 제한만 적용하지 않는다. page=0, size=20(최대 100), 기본 createdAt desc 및 ID desc이며 createdAt·updatedAt·publishedAt의 asc/desc와 같은 방향 ID 보조 정렬을 지원한다. 페이지 offset은 signed INT 범위 안이어야 한다.

검수 목록은 공통 페이지 응답이며 각 항목과 상세/차단 응답은 `{content, blockedAt, blockedByAdminId, deletedAt}`이다. content는 기존 안전한 Post/Project DTO에 isBlocked를 포함한 값이다. 목록은 본문 블록·Project 상세 필드를 생략하고 상세는 삭제된 콘텐츠의 보존된 본문·기능·링크·Tag도 제공한다. blockedByAdminId는 내부 ID 문자열 또는 null이며 OAuth 식별자·인증 비밀은 제공하지 않는다. likeCount는 기존 공개 가능한 콘텐츠 집계만 반영하고 관리자 likedByMe는 false다.

차단 PUT/DELETE는 200과 검수 상세 응답을 반환한다. 차단과 해제는 본문 없이 상태만 설정한다.

- PUT block은 isBlocked=true, blockedAt=현재 시각, blockedByAdminId=요청 관리자 ID로 설정한다.
- DELETE block은 isBlocked=false로 설정하고 blockedAt과 blockedByAdminId를 비운다.
- 이미 차단된 리소스에 PUT, 차단되지 않은 리소스에 DELETE를 보내도 멱등하게 200을 반환한다. 같은 차단 상태는 최초 blockedAt·blockedByAdminId·updatedAt을 보존한다.
- DELETED 콘텐츠는 차단 상태 변경을 거부하고 CONTENT_DELETED를 반환한다.
- 차단과 차단 해제는 visibilityStatus를 변경하지 않는다.
- 차단 메타데이터는 현재 차단 상태만 기록한다. 과거 차단 이력은 MVP에서 제공하지 않는다.

관리자 DELETE 상세는 visibilityStatus=DELETED의 논리 삭제이며 본문 없는 204를 반환한다. 이미 삭제된 콘텐츠도 204이며 최초 deletedAt을 보존한다. 복구 API는 없고 본문·Tag·댓글·좋아요 이력은 물리 삭제하지 않는다. Project 강제 삭제는 숨김·차단·삭제 Post를 포함한 모든 연결의 projectId를 같은 트랜잭션에서 null로 만들며 글의 공개 상태와 내용을 보존한다. 차단/삭제는 기존 공개 상세·목록·검색·댓글·좋아요 판정에서 제외하고 차단 해제 시 이력은 다시 공개 규칙으로 집계한다. 허용 Origin의 정확한 목록 GET·상세 GET/DELETE·block PUT/DELETE CORS만 등록하며 새 CSRF 예외는 없다.

### 6.6 Category, Tag와 Board

Category 응답 항목: `{id, parentId, name, slug, displayOrder, status, children}`. name 최대 50자, slug 최대 100자다. 최대 깊이는 2단계이며 Post에는 최하위 Category만 지정한다.

`GET /categories`의 data는 최상위 Category 배열이고 각 children은 하위 Category 배열이다. `GET /tags`의 data는 Tag 배열이다. 빈 결과는 `[]`이며 ID·parentId는 문자열, 최상위 parentId는 null, 하위가 없는 children은 `[]`로 반환한다. Category 형제와 Tag 목록은 displayOrder 오름차순, 같은 순서는 숫자 ID 오름차순으로 정렬한다.

Tag 응답 항목: `{id, name, slug, displayOrder, status}`. name 최대 50자, slug 최대 100자다. 비활성 항목은 기존 공개 콘텐츠에서 참조되는 경우 탐색 결과에 표시될 수 있다.

`POST /tags`는 ACTIVE·프로필 완료 USER Bearer가 자유 태그를 등록하는 API다. 요청은 정확히 `{name:string}`이며 query·추가/중복 JSON 필드·추가 JSON 문서·null은 400이다. 이름은 NFKC → 앞뒤 공백 제거 → 선두 `#` 한 개 제거 → Locale.ROOT 소문자 순서로 정규화한다. 정규화 결과는 1~50 유니코드 코드 포인트이며 Unicode 문자·숫자·결합 문자와 `_`, `-`, `.`, `+`만 허용하고 문자 또는 숫자를 하나 이상 포함해야 한다. NUL·잘못된 Unicode·내부 공백·HTML은 400이며 `C++`는 허용하고 `C#`는 거부한다.

대소문자를 무시한 기존 이름이 있으면 ACTIVE를 우선하고 같은 상태에서는 숫자 ID가 가장 작은 태그를 재사용한다. INACTIVE만 있으면 `INACTIVE_TAG` 409이며 재활성화하지 않는다. 신규 태그는 `user-` + 정규화 이름의 SHA-256 64자리 소문자 hex slug, displayOrder=0, ACTIVE로 생성한다. 기존 slug 고유 제약과 원자적 INSERT로 서로 다른 회원의 동시 생성도 같은 ID를 반환하며 생성 시 createdAt·updatedAt은 같은 시각이다. hash slug를 다른 이름의 ACTIVE 태그가 선점하면 `TAG_SLUG_CONFLICT` 409로 거부하고, 선점 태그가 INACTIVE면 `INACTIVE_TAG`가 우선한다. 생성·재사용 모두 200과 기존 Tag 응답을 반환하며 기존 태그의 이름·slug·순서·상태·시각을 바꾸지 않는다. 반환 ID는 기존 Post/Project `tagIds`에 연결할 수 있다. 정지·탈퇴 대기는 403, 미완료 프로필은 `PROFILE_REQUIRED` 409다.

Category 트리는 활성 하위의 경로를 보존하기 위해 비활성 상위를 `INACTIVE` 상태인 그룹으로 포함할 수 있다. 이 그룹은 신규 선택 대상이 아니다. 사용 중이지 않은 비활성 하위와 Tag는 제외한다. 신규 Post 연결은 ACTIVE이고 저장된 하위 Category가 없는 항목만 허용하며, 공개 children이 비었다는 이유만으로 최하위라고 판단하지 않는다. Post는 Category·Tag를 참조하며, 공개 분류 필터에서 상위 Category를 지정하면 하위 Category에 연결된 공개 Post를 포함한다. Post가 사용 중인 비활성 분류도 공개 탐색에서 유지한다.

초기 데이터는 상위 주제 Backend·Frontend·Database·DevOps·Architecture와 기술 Tag Java·Spring Boot·JPA·JavaScript·React·HTML·CSS·PostgreSQL이다. 하위 주제는 관리자 관리 API에서 필요에 따라 구성한다. 분류 조회는 활성 항목과 활성 하위의 상위 그룹을 제공하며, Post가 사용하는 비활성 분류도 공개 탐색에 포함한다. Category·Tag 관리자 조회·생성·수정 API를 제공한다.

Category 생성 요청은 `{parentId, name, slug, displayOrder}`이며 Tag 생성 요청은 `{name, slug, displayOrder}`다. 수정 요청은 각 생성 필드와 status를 부분 변경한다. status 값은 ACTIVE 또는 INACTIVE다. 참조 항목을 물리 삭제하는 API는 없다.

관리자 GET은 비활성·미참조 항목까지 포함한 전체 Category 트리/Tag 배열을 같은 displayOrder·숫자 ID 순서로 반환하며 페이지·필터·본문은 받지 않는다. POST는 name·slug 필수, parentId 기본 null, displayOrder 기본 0이며 ACTIVE로 생성하고 status 입력은 400이다. 201과 생성 항목의 숫자 ID 운영 주소 Location을 반환한다. PATCH는 누락 필드 유지, parentId의 명시적 null은 루트 이동이며 나머지 null은 400이다. 빈 PATCH는 기존 갱신 시각을 보존하는 200이고 생성·수정 응답은 기존 Category/Tag DTO를 사용한다.

name은 앞뒤 공백 제거 후 1~50 유니코드 코드 포인트이며 NUL·잘못된 Unicode를 거부한다. slug는 소문자로 정규화하고 영문·숫자·단어 사이 하이픈 1~100자를 허용한다. displayOrder는 0 이상의 INT, parentId·경로 ID는 선행 0 없는 양의 BIGINT 문자열이다. 알 수 없는·중복 JSON 필드·추가 JSON·모든 query·GET 본문과 잘못된 값은 400, 없는 ID는 404다. 관리자 상세 GET·DELETE는 제공하지 않는다.

동일 종류의 slug는 비활성 항목까지 포함해 유일하며 충돌은 CATEGORY_SLUG_CONFLICT/TAG_SLUG_CONFLICT 409다. Category 자기 부모·순환·2단계 초과와 저장된 하위를 가진 루트의 하위 이동은 CATEGORY_HIERARCHY_CONFLICT 409다. 비활성 하위도 깊이 검사에 포함한다. 모든 상태 Post가 참조하는 Category에 하위를 추가·이동하는 요청도 같은 409로 거부해 기존 연결을 유지한다. 비활성화는 기존 Post·Project 연결을 보존하지만 신규 선택은 기존 공개 계약에 따라 거부한다.

내 Board 응답은 `{id, parentId, name, displayOrder, children}` 트리이고 공개 Board 응답은 `{id, name, displayOrder, children}` 트리로 parentId를 생략한다. POST와 PATCH는 단일 항목 `{id, parentId, name, displayOrder}`을 반환하며 children은 포함하지 않는다. ID는 문자열이고 루트의 parentId는 null, children이 없는 항목의 children은 `[]`다. Board와 children은 displayOrder 오름차순, 같은 값이면 숫자 ID 오름차순이다.

Board 생성 요청은 `{name, parentId, displayOrder}`다. name은 공백이 아닌 문자열이며 최대 50 유니코드 코드 포인트다. parentId는 양의 10진 문자열 또는 null이고, displayOrder는 0 이상의 정수다. 생성 시 name은 필수이며 parentId 기본값은 null, displayOrder 기본값은 0이다. 수정은 필드별 부분 변경이며 누락은 기존 값 유지, parentId의 명시적 null은 루트 이동이다. displayOrder는 형제 정렬 키이며 항목을 이동시키거나 다른 항목의 순서를 자동으로 밀지 않는다.

부모 변경 시 같은 소유자 Board인지, 최대 3단계와 순환 참조를 위반하지 않는지, 같은 부모 아래에 대소문자까지 동일한 이름이 이미 있는지 검증한다. 이름 중복은 400 VALIDATION_ERROR다. 삭제되지 않은 Board의 이름만 중복 검증에 참여하므로 삭제한 이름은 재사용할 수 있다. 부모 또는 Board를 찾을 수 없거나 다른 소유자의 Board이면 404다. 하위 미삭제 Board가 남은 삭제 요청은 409 RESOURCE_HAS_CHILDREN이다. 삭제는 Board를 논리 삭제하고 같은 트랜잭션에서 연결 Post의 boardId를 null로 만든다. ACTIVE USER는 프로필 완료 없이 자신의 Board를 관리할 수 있다. 공개 Board 트리는 빈 Board도 포함하며 WITHDRAWAL_PENDING 소유자는 404다. SUSPENDED 소유자의 공개 데이터는 유지한다. Board별 Post 목록은 PUBLIC·비차단·미삭제 Post만 포함한다. Post 작성·수정은 기존과 같이 프로필 완료가 필요하다.

### 6.7 댓글과 좋아요

Post·Project 좋아요와 일반 회원 댓글 API, 관리자 운영 댓글 조회·삭제를 제공한다. 대댓글은 후속 기능이다.

댓글 생성 요청:

~~~json
{
  "body": "도움이 되는 글이네요.",
  "visibility": "PUBLIC"
}
~~~

- body는 필수, 최대 2,000자다.
- visibility는 PUBLIC 또는 SECRET이다.
- POST는 body·visibility를 모두 받고 201 Created와 댓글 단일 주소 Location을 반환한다. PATCH는 body·visibility의 부분 수정이며 생략은 기존 값 유지, 명시적 null·알 수 없는 필드는 400이다. 빈 PATCH는 200으로 기존 값을 반환한다. body는 공백 전용을 거부하고 최대 2,000 유니코드 코드 포인트다. NUL·잘못된 유니코드는 400이다.
- ACTIVE USER는 프로필 완료 없이 작성·본인 수정·삭제를 수행한다. 댓글 대상·부모 소유권은 경로의 콘텐츠 종류와 ID로 검증하며 타인 댓글 수정·삭제와 잘못된 부모 경로는 404다. 본인 댓글은 부모가 HIDDEN·차단이어도 수정·삭제할 수 있으나 DELETED·탈퇴 대기 소유자 대상은 404다.
- DELETE는 204이며 본문을 빈 문자열로 제거하고 삭제 시각을 기록한다. 삭제 댓글 조회·수정·반복 삭제는 404다. DELETE 본문과 목록 외 경로의 query parameter는 허용하지 않는다.
- 목록은 CommentPage(content·page·size·totalElements·totalPages·hasNext·hasPrevious)를 반환한다. page 기본 0, size 기본 20·최대 100, sort 기본 createdAt,asc이며 createdAt·updatedAt과 asc·desc만 허용한다. ID를 같은 방향 보조 정렬로 사용한다. 공개 범위·작성자 상태를 적용한 뒤 집계·페이징하고, 알 수 없는·중복 query parameter는 400이다.
- 작성, 좋아요는 PUBLIC이고 차단되지 않은 콘텐츠에서만 허용한다.
- Guest는 PUBLIC 댓글만 조회하며, 대상 콘텐츠도 PUBLIC이고 차단되지 않아야 한다.
- SECRET 댓글은 댓글 작성자, 대상 콘텐츠 작성자, MANAGER와 MASTER만 조회할 수 있다.
- USER는 본인 댓글만 수정·삭제한다. 작성자는 자신의 콘텐츠에 달린 SECRET 댓글을 읽을 수 있지만 수정·삭제할 수 없다.
- MANAGER와 MASTER는 운영 목록에서 모든 댓글을 조회하고 삭제할 수 있다. 댓글 수정은 불가하다.
- 대상 콘텐츠가 HIDDEN 또는 차단 상태이면 Guest와 일반 회원은 댓글 목록을 조회할 수 없다. 콘텐츠 작성자와 관리자는 관리 조회를 할 수 있다. SECRET 댓글 작성자는 단일 댓글 조회 경로에서 자신의 댓글을 읽을 수 있다.
- 공개 댓글은 대상 콘텐츠가 PUBLIC이고 차단되지 않은 경우에만 Guest에게 노출한다. SECRET 댓글의 접근 권한은 대상 콘텐츠 상태와 별도로 댓글 작성자, 콘텐츠 작성자, MANAGER, MASTER에게 부여한다.
- DELETED 대상 콘텐츠의 댓글은 관리자 운영 경로 외에는 반환하지 않는다.
- 삭제 댓글 본문은 어떤 응답에도 포함하지 않는다.
- 탈퇴 대기 콘텐츠 소유자의 댓글 영역과 탈퇴 대기 작성자의 댓글은 일반 경로에서 404 또는 목록 제외 처리한다. 정지 작성자의 기존 PUBLIC 댓글은 공개 대상에서 유지한다. 비활성 USER의 공개 목록은 PUBLIC만 제공하고 본인 SECRET·소유자의 HIDDEN/차단 관리 접근에는 ACTIVE 검증을 적용한다. 본인 SECRET 작성자는 부모가 HIDDEN·차단이어도 단일 조회가 가능하지만 목록 전체 권한을 얻지는 않는다.

댓글 응답 항목은 `{id, author, body, visibility, createdAt, updatedAt}`다. 관리자는 삭제된 댓글의 운영 메타데이터를 볼 수 있지만 삭제 본문을 복구할 수 없다.

관리자 댓글 목록은 GET `/api/v1/admin/post-comments`와 `/api/v1/admin/project-comments`다. 현재 ACTIVE MANAGER/MASTER만 접근하며 부모·작성자 상태와 무관하게 SECRET·삭제 댓글을 포함한다. 각각 postId 또는 projectId와 authorId·visibility(PUBLIC/SECRET)·deleted(true/false) 필터를 선택할 수 있고 미지정 필터는 전체다. AdminContentPage 형식과 page 기본 0·size 기본 20/최대 100을 사용한다. sort는 createdAt,asc 또는 createdAt,desc만 허용하며 기본은 desc이고 ID를 같은 방향 보조 정렬로 사용한다. 페이지 offset은 signed INT 이하이며 ID는 선행 0 없는 양의 BIGINT 문자열이다. 알 수 없는·중복 query와 GET 본문은 400이다.

운영 댓글 항목은 `{id, contentId, author, body, visibility, createdAt, updatedAt, deletedAt}`다. author는 일반 댓글과 같은 안전한 표시 정보만 제공하며 삭제 댓글은 body 필드를 생략한다. DELETE `/api/v1/admin/{post-comments|project-comments}/{commentId}`는 본문을 지우고 최초 deletedAt·updatedAt을 보존하는 멱등 204다. 없는 ID는 404, 잘못된 숫자 ID·본문·query는 400이다. 관리자 상세 GET·작성·수정·복원 경로는 제공하지 않는다. 삭제는 현재 관리자 읽기 잠금 뒤 댓글 단독 쓰기 잠금으로 작성자 수정과 직렬화하며 부모·회원 상태를 변경하거나 해당 행을 잠그지 않는다.

좋아요 PUT·DELETE 요청은 본문과 query parameter가 없으며 둘 다 204 No Content를 반환한다. 중복 등록·취소는 멱등 처리한다. ACTIVE USER는 프로필 완료 없이 호출할 수 있다. 대상이 PUBLIC·미차단·미삭제·비탈퇴 소유자 조건을 만족하지 않거나 존재하지 않으면 등록·취소 모두 404다. SUSPENDED·WITHDRAWAL_PENDING 요청 회원은 403이며 Bearer 없는 쓰기는 기존 CSRF 방어에서 거부된다.

Post·Project 상세와 모든 목록·검색 응답은 공개 가능한 콘텐츠의 활성 좋아요를 집계한 likeCount와 요청 USER의 likedByMe를 제공한다. 탈퇴 대기 회원의 좋아요는 집계·likedByMe에서 제외하며 정지 회원의 기존 좋아요는 유지한다. Guest·관리자는 likedByMe=false다. 소유자가 조회하는 숨김·차단 콘텐츠도 집계는 0·false다. 콘텐츠 숨김·차단·논리 삭제는 좋아요 이력을 보존하고, 재공개하면 유효한 기존 좋아요가 다시 집계된다. 취소는 논리 삭제하며 다시 등록하면 새 행을 추가한다. MANAGER와 MASTER는 좋아요를 등록·취소할 수 없다. 관리자 인증은 6.5절을 따른다.

### 6.8 미디어

Post 썸네일 및 Project 미디어 업로드는 multipart/form-data를 사용하고 원본 파일은 처리 중에만 사용해 R2에 WebP 파생 파일만 저장한다. 명시적으로 R2를 활성화하지 않은 환경의 업로드는 STORAGE_UNAVAILABLE 503이다.

- Post 썸네일 업로드 필드: file
- Project 미디어 업로드 필드: file, mediaRole, 선택적 altText, displayOrder
- mediaRole은 THUMBNAIL 또는 SCREENSHOT이다. Project당 THUMBNAIL은 하나만 허용한다.
- 업로드 성공 응답은 미디어 ID, role, altText, displayOrder, 접근 가능한 signed URL을 반환한다. Project 미디어 응답 data는 `{id, mediaRole, altText, displayOrder, url, thumbnailUrl, createdAt}`다. Post 썸네일 업로드 응답 data는 `{thumbnailUrl}`이다.
- 입력은 정적 JPEG(.jpg, .jpeg), PNG, WebP만 허용한다. SVG, GIF와 애니메이션 이미지는 지원하지 않는다. 파일당 원본 크기는 최대 10 MiB, 디코딩한 이미지 총 픽셀은 최대 20,000,000, 가로와 세로 각각 최대 8,000 px다. MIME 헤더만 신뢰하지 않고 실제 파일 서명과 디코더 결과를 검증한다.
- 입력 이미지의 종횡비를 유지하고 확대하지 않는다. 화면 표시용 파생 이미지는 긴 변 최대 2,560 px, 목록용 썸네일은 긴 변 최대 480 px로 만든다. 두 파생물 모두 WebP lossy quality 82로 저장하며 EXIF 등 불필요한 메타데이터를 제거한다. 원본은 저장하지 않는다.
- 모든 입력에 WebP lossy quality 82를 일괄 적용한다. 손실 압축은 파일 크기를 줄이는 대신 일부 시각 정보를 버리므로 작은 글자나 얇은 선이 많은 PNG 스크린샷·도식은 경계가 부드러워지거나 압축 흔적이 보일 수 있다. 이는 MVP의 저장 효율을 위한 품질 절충이며 원본은 보관하지 않는다.
- Post의 `thumbnailUrl`은 480 px 썸네일이다. Project의 `url`은 화면 표시용 이미지이고 `thumbnailUrl`은 목록과 미리보기용 파생 이미지다. R2에는 비공개 버킷과 서버 생성 키를 사용한다.
- 업로드·수정·삭제는 ACTIVE·프로필 완료 USER이며 해당 콘텐츠 작성자여야 한다. 회원 → 콘텐츠 잠금 아래 처리하고 다른 작성자 대상은 404, 삭제된 콘텐츠는 CONTENT_DELETED 409다. 업로드 query·중복/알 수 없는 multipart 필드·추가 파일은 400으로 거부한다. Project 업로드의 mediaRole·displayOrder는 필수이며 displayOrder는 0 이상 정수다. PATCH는 altText·displayOrder만 지원하며 altText=null은 설명 제거다. 대표 이미지 중복은 THUMBNAIL_ALREADY_EXISTS 409다.
- HIDDEN·차단 콘텐츠의 소유자는 미디어를 관리할 수 있지만 새 서명 URL은 발급하지 않아 url·thumbnailUrl은 null이다. Project 목록은 미디어 배열을 생략하고 상세에서만 순서·ID 안정 정렬로 반환한다. R2 비활성 환경도 URL을 발급하지 않는다.
- 교체·삭제·콘텐츠 논리 삭제·회원 물리 파기는 DB 트랜잭션과 함께 삭제 큐를 기록한다. 삭제 작업은 기본 1분 주기·최대 100개·SKIP LOCKED로 처리하고 저장소 오류는 5분 뒤 재시도한다. 실패한 업로드/DB 롤백의 새 객체는 업로드 전에 커밋한 회수 작업으로 1시간 뒤 회수하며 연결 트랜잭션 중에는 삭제하지 않는다.
- signed GET URL은 발급 시점부터 15분 유효하다. 콘텐츠를 숨김·차단·삭제하거나 회원이 탈퇴 예약한 뒤 새 URL을 발급하지 않는다. 이미 발급된 URL은 만료 전까지 사용할 수 있다. 화면에 이미 내려받은 이미지는 URL 만료 시점에 사라지지 않으며, 만료 후 새로 필요한 이미지는 권한을 다시 확인한 리소스 조회로 URL을 갱신한다.

## 7. 목록·검색 query parameter

| Resource | 지원 필터 |
|---|---|
| GET /posts | categoryId, tagId, authorId |
| GET /posts/search | q 필수, categoryId, tagId, authorId |
| GET /blogs/{handle}/posts | categoryId, tagId |
| GET /projects | tagId, lifecycleStatus |
| GET /projects/search | q 필수, tagId, lifecycleStatus |
| GET /members/{memberId}/posts | categoryId, tagId |
| GET /members/{memberId}/projects | tagId, lifecycleStatus |
| GET /members/me/posts | visibilityStatus |
| GET /members/me/projects | visibilityStatus |
| GET /admin/posts | q, visibilityStatus, isBlocked, authorId |
| GET /admin/projects | q, visibilityStatus, isBlocked, ownerId |
| GET /admin/members | status, q |
| GET /admin/post-comments | postId, authorId, visibility, deleted |
| GET /admin/project-comments | projectId, authorId, visibility, deleted |

- q는 앞뒤 공백을 제거한 비어 있지 않은 검색어다. Post·Project 검색에서는 최대 200 유니코드 코드 포인트를 허용하며 누락·공백 전용·길이 초과·NUL·잘못된 유니코드는 400 VALIDATION_ERROR다.
- 알 수 없는 query parameter와 같은 parameter의 중복 전달은 400이다. 각 목록의 필터와 sort 허용값은 endpoint 계약에 따른다.
- 목록 endpoint는 공통으로 page(기본 0), size(기본 20, 최대 100), sort를 지원한다. GET /posts 기본 정렬은 publishedAt 내림차순이고 허용 sort 필드는 publishedAt, createdAt이다. 블로그 목록은 displayOrder 오름차순이며 허용 필드는 displayOrder, publishedAt, createdAt이다. 정렬 방향은 asc 또는 desc다.
- GET /projects의 기본 정렬은 publishedAt 내림차순이고 publishedAt, createdAt 정렬을 허용한다. tagId와 lifecycleStatus는 함께 사용할 수 있다.
- Post 검색은 제목, 본문 블록 content와 title, 연결 Category 및 상위 Category의 name, Tag.name에서 대소문자를 구분하지 않는 부분 문자열을 찾는다. 검색 대상 사이는 OR이고 q와 categoryId·tagId·authorId 필터 사이는 AND다. summary·slug는 검색 대상이 아니다. `%`, `_`, 역슬래시는 와일드카드가 아니라 문자 그대로 검색한다. 여러 블록·Tag가 일치해도 같은 Post와 totalElements를 중복하지 않는다.
- 검색은 PostPage 목록 응답을 사용하며 본문 blocks와 소유자 전용 isBlocked는 포함하지 않는다. 공개 필터와 검색 조건을 적용한 뒤 페이징한다. GET /posts/search는 GET /posts와 같은 page·size·sort 계약을 따른다. Board·Project 필터는 현재 Post 목록과 검색에서 지원하지 않는다.
- Board별 Post 목록은 지정 Board의 직접 연결만 조회하며 하위 Board의 글을 합치지 않는다.
- 본인 Post 목록의 visibilityStatus는 PUBLIC 또는 HIDDEN이며 DELETED는 400이다.
- Project 검색 대상은 name, summary, description, Tag.name이다. 대소문자 구분 없는 부분 문자열 OR 검색이며 %, _, 역슬래시는 문자 그대로 찾는다. 검색어와 tagId·lifecycleStatus 필터는 AND로 적용하고 중복 없이 ProjectPage를 반환한다. 검색·회원별 공개·본인 Project 목록은 공개 전체 목록과 같은 page·size·sort 계약을 따른다.
- 본인 Project 목록은 ACTIVE USER만 조회하며 PUBLIC/HIDDEN 및 차단 콘텐츠를 포함하고 isBlocked를 제공한다. visibilityStatus는 PUBLIC/HIDDEN만 허용한다. 회원별 공개 목록은 존재하지 않거나 WITHDRAWAL_PENDING 회원에 404를 반환한다.
- GET /projects/{projectId}/posts는 PUBLIC·미차단·비탈퇴 소유자 Project에 직접 연결된 공개 Post만 PostPage로 반환한다. Project 공개 조건을 만족하지 않으면 소유자 요청에도 404다. page·size·sort만 허용하며 기본 publishedAt 내림차순, 허용 정렬 publishedAt·createdAt와 같은 방향 ID 순서다. Project 논리 삭제는 모든 Post(숨김·차단·삭제 포함)의 연결을 같은 트랜잭션에서 해제하며 글과 공개 상태를 보존한다.
- Guest 검색은 PUBLIC이고 차단되지 않은 콘텐츠만 대상으로 한다.
- 검색은 MVP의 기본 문자열 검색을 제공한다. 자동 완성, 검색어 추천, 개인화, 전문 검색 엔진은 제공하지 않는다.
- USER 요청의 visibilityStatus 필터는 본인 콘텐츠 목록에서만 사용할 수 있다. 공개 사용자 목록의 필터는 PUBLIC/차단 제외를 우회하지 않는다.
- 검색·댓글 공개 조회·좋아요 집계는 차단된 콘텐츠를 포함하지 않는다. 콘텐츠 소유자의 댓글 관리 조회와 본인 SECRET 단일 조회 예외는 6.7절을 따른다.

## 8. API 전역 제약

- API는 회원이 다른 회원의 Post, Project, Board, 댓글을 수정 또는 삭제하지 못하게 한다.
- isBlocked, blockedAt, blockedByAdminId는 USER 생성·수정 요청으로 변경할 수 없다. Post/Project 관리자 조회·차단 API는 6.5절을 따른다.
- 구현된 리소스의 관리자 강제 삭제는 visibilityStatus를 DELETED로 전환한다. DELETED 복구 API는 없다. Post/Project 관리자 강제 삭제는 6.5절을 따른다.
- 탈퇴 요청 후 7일 동안 취소할 수 있으며, 요청 즉시 계정 이용과 콘텐츠 공개를 막는다. 예약 기간이 끝나면 계정과 콘텐츠를 영구 삭제한다.
- 업로드 형식, 크기·픽셀 한도, WebP 파생 크기와 품질, signed GET URL 15분 만료 정책은 6.8절을 따른다.

## 글쓰기 초안·본문 이미지 (API #76 / Web #25)

Post 생성/수정은 선택 boolean `draft`를 받으며 응답에 해당 상태를 포함한다. 생략한 생성은 기존대로 false다. true로 생성한 글은 HIDDEN이어야 한다. 초안은 HIDDEN으로 유지하며, `draft=false`와 발행 범위를 보내 확정한다. 확정된 글에 draft=true를 보내거나 slug를 변경할 수 없다. 초안 확정의 slug 중복은 새 글 생성과 같은 회원 행 잠금·접미사 정책으로 처리한다. 기존 발행 글 편집의 임시저장은 브라우저 초안으로 구분하며 공개 API 저장을 하지 않는다.

- POST `/api/v1/posts/{postId}/images`: USER, 프로필 완료 소유자, multipart `file`만 입력. 기존 JPEG/PNG/WebP·10MiB·실제 디코딩/픽셀 제한을 적용하고 글당 최대 50개 WebP 본문 이미지를 저장한다. `{id,url,altText:null}`를 반환한다. url은 편집 미리보기용 만료되는 서명 URL이다.
- GET `/api/v1/posts/{postId}/images`: 활성 USER 소유자만 이미지 배열과 새 서명 URL을 받는다. query/본문은 없다.
- DELETE `/api/v1/posts/{postId}/images/{imageId}`: 프로필 완료 소유자만 제거한다. 삭제 큐로 파일을 회수한다. query/본문은 없다.
- GET `/api/v1/posts/{postId}/images/{imageId}/content`: 공개·미차단·미탈퇴 글의 실제 HTML/MARKDOWN 본문에 연결된 이미지에만 새 서명 URL로 302 연결한다. `Cache-Control: no-store`이며 숨김/초안/미연결/삭제는 404다. query/본문은 없다. 저장 본문에는 이 고정 경로를 사용하며 서명 URL을 영구 저장하지 않는다. 대체 텍스트는 본문의 img alt 또는 Markdown 이미지 설명으로 저장한다.

업로드 중 DB/외부 저장 실패는 사전 커밋한 삭제 큐로 회수한다. 글 논리 삭제·물리 삭제·회원 파기 때 본문 이미지 연결과 외부 파일 정리도 적용한다. 본문에서 이미지 문법만 지워도 업로드 파일은 작성자가 명시적으로 지우거나 글을 삭제할 때까지 글의 첨부로 유지된다. 이미 발급된 서명 URL은 기존 CDN/스토리지 정책대로 만료 전 잠시 사용 가능하며 즉시 무효화와 동일하지 않다.

## 개발용 생성 API 문서

컨트롤러·DTO 기반 OpenAPI JSON과 회원/관리자 Swagger UI를 개발 환경에서 선택적으로 제공한다. [OPENAPI.md](OPENAPI.md)에 실행·인증·프론트 협업 방법을 정리했다. 오류 코드·상태 전이는 이 API 계약이 기준이며 생성 스키마와 함께 확인한다. 운영에는 문서를 공개하지 않는다.

## OAuth 공통화와 기존 네이버 API

네이버 authorization/login/withdrawal-cancel 경로·요청·응답·state 쿠키와 Refresh 쿠키는 유지한다. 내부 처리는 공급자를 명시하는 공통 OAuthLoginService로 위임한다. 이 변경은 카카오/구글 로그인 API를 추가하지 않는다. 새로운 공급자는 서버 등록과 정확한 HTTP·쿠키·CORS/Origin/CSRF 및 프론트 callback 계약을 별도 작업에서 추가한다.
