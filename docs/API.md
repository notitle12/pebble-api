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
- Post·Project 목록은 publishedAt 또는 createdAt, 회원·관리자 관리 목록은 createdAt 또는 updatedAt, 댓글은 createdAt 정렬을 허용한다. Category·Tag·Board와 Project 하위 항목은 displayOrder 정렬을 사용한다.
- 공개 Post·Project 목록 및 검색은 기본적으로 publishedAt 내림차순, ID 내림차순이다.
- 회원의 관리 목록은 기본적으로 createdAt 내림차순, ID 내림차순이다.
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
| GET | /api/v1/members/me | USER | 내 계정 조회 |
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
| GET | /api/v1/posts/search | Guest | 공개 Post 검색 |
| GET | /api/v1/posts/{postId} | Guest, 작성자, MANAGER, MASTER | 공개 Post 또는 권한 있는 상세 조회 |
| POST | /api/v1/posts | USER | Post 생성 |
| PATCH | /api/v1/posts/{postId} | 작성자 | 본인 Post 수정 |
| DELETE | /api/v1/posts/{postId} | 작성자 | 본인 Post 논리 삭제 |
| PUT | /api/v1/posts/{postId}/thumbnail | 작성자 | 썸네일 업로드·교체 |
| DELETE | /api/v1/posts/{postId}/thumbnail | 작성자 | 썸네일 제거 |

### 5.3 Project

| Method | Path | 접근 | 설명 |
|---|---|---|---|
| GET | /api/v1/projects | Guest | 공개 Project 목록 |
| GET | /api/v1/projects/search | Guest | 공개 Project 검색 |
| GET | /api/v1/projects/{projectId} | Guest, 작성자, MANAGER, MASTER | 공개 Project 또는 권한 있는 상세 조회 |
| POST | /api/v1/projects | USER | Project 생성 |
| PATCH | /api/v1/projects/{projectId} | 작성자 | 본인 Project 수정 |
| DELETE | /api/v1/projects/{projectId} | 작성자 | 본인 Project 논리 삭제 |
| POST | /api/v1/projects/{projectId}/media | 작성자 | 대표 이미지 또는 스크린샷 추가 |
| PATCH | /api/v1/projects/{projectId}/media/{mediaId} | 작성자 | 미디어 설명·순서 수정 |
| DELETE | /api/v1/projects/{projectId}/media/{mediaId} | 작성자 | Project 미디어 제거 |

### 5.4 Board

| Method | Path | 접근 | 설명 |
|---|---|---|---|
| GET | /api/v1/members/me/boards | USER | 내 Board 구조 |
| POST | /api/v1/boards | USER | Board 생성 |
| PATCH | /api/v1/boards/{boardId} | 소유자 | 이름, 부모, 순서 수정 |
| DELETE | /api/v1/boards/{boardId} | 소유자 | Board 삭제. Post는 미분류 처리 |

### 5.5 Category와 Tag

| Method | Path | 접근 | 설명 |
|---|---|---|---|
| GET | /api/v1/categories | Guest | 공개 탐색용 Category 트리 |
| GET | /api/v1/tags | Guest | 공개 탐색용 Tag 목록 |
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
| GET | /api/v1/posts/{postId}/comments/{commentId} | 댓글 작성자, 콘텐츠 작성자, MANAGER, MASTER | 권한이 있는 단일 댓글 조회 |
| PATCH | /api/v1/posts/{postId}/comments/{commentId} | 댓글 작성자 | 본인 댓글 수정 |
| DELETE | /api/v1/posts/{postId}/comments/{commentId} | 댓글 작성자 | 본인 댓글 논리 삭제 |
| GET | /api/v1/projects/{projectId}/comments | Guest, USER, 작성자, MANAGER, MASTER | 댓글 목록. SECRET 접근 규칙 적용 |
| POST | /api/v1/projects/{projectId}/comments | USER | 댓글 생성 |
| GET | /api/v1/projects/{projectId}/comments/{commentId} | 댓글 작성자, 콘텐츠 작성자, MANAGER, MASTER | 권한이 있는 단일 댓글 조회 |
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
      "role": "USER"
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

관리자 로그인 요청은 `{loginId, password}`를 받는다. 성공 응답은 Access Token과 `admin: {id, loginId, role, status}`를 data에 반환하고 관리자 Refresh Token은 별도의 `HttpOnly; Secure; SameSite=Lax` 쿠키로 설정한다. 관리자(MANAGER, MASTER) Refresh Token은 비활성 1시간, 최초 로그인부터 절대 24시간 동안 유효하다. 관리자 Access JWT의 만료 시간도 900초(15분)다. 관리자 refresh와 로그아웃은 관리자 Refresh Cookie를 사용하며 JSON 본문으로 Refresh Token을 받지 않는다. 회전 시 쿠키 `Max-Age`는 비활성 만료와 남은 절대 만료 기간 중 짧은 쪽으로 갱신한다. 관리자 로그아웃은 해당 Refresh Token Family와 Redis의 `sid`를 폐기하고 쿠키를 만료시키므로 그 세션의 Access JWT는 다음 관리자 인증 요청부터 거부한다. 관리자 계정 생성은 `{loginId, initialPassword}`를 받고 새 계정 role은 MANAGER로 고정한다. MASTER 계정은 이 API로 생성하지 않는다.

관리자 회원 상태 변경 요청은 `{status}`를 받으며 값은 ACTIVE 또는 SUSPENDED다. `WITHDRAWAL_PENDING` 전환과 취소는 회원 탈퇴 API만 수행한다. 탈퇴 유예 기간이 끝나면 회원 레코드를 물리 삭제하므로 `WITHDRAWN` 상태는 저장하지 않는다. 관리자 계정 상태 변경은 `{status}`를 받으며 ACTIVE 또는 INACTIVE다.

회원 상세 응답:

`GET /members/me`는 USER Access JWT의 검증된 `sub`에서 본인 회원 ID를 식별한다. 요청으로 조회할 회원 ID를 받지 않는다. 현재 DB의 ACTIVE 회원만 아래 정보를 조회할 수 있다. 인증 누락은 `AUTHENTICATION_REQUIRED` 401, 무효·만료 토큰은 `INVALID_TOKEN` 401, 회원이 없으면 `RESOURCE_NOT_FOUND` 404, 정지 상태는 `ACCOUNT_SUSPENDED` 403, 탈퇴 대기 상태는 `ACCOUNT_WITHDRAWAL_PENDING` 403을 반환한다. Refresh Cookie만으로 이 API에 인증할 수 없다. 허용한 프런트엔드 Origin의 GET과 Authorization 헤더 preflight를 지원한다.

~~~json
{
  "data": {
    "id": "721389012345678901",
    "nickname": "pebble",
    "profileImageUrl": null,
    "status": "ACTIVE",
    "createdAt": "2026-09-30T03:00:00Z"
  }
}
~~~

`DELETE /members/me`는 Refresh Token Family를 즉시 폐기하고 Refresh Cookie를 만료시킨 뒤 회원을 `WITHDRAWAL_PENDING`으로 전환한다. 응답은 202이며 `withdrawalScheduledAt`에 삭제 예정 시각을 반환한다. 탈퇴 예약 뒤에는 기존 Access JWT의 만료 여부와 관계없이 보호된 USER 요청을 거부하고 회원의 콘텐츠·댓글·좋아요·미디어를 일반 사용자에게 숨긴다. Naver authorization code를 다시 검증하는 `POST /auth/naver/withdrawal/cancel`로 예약 후 7일 이내 취소할 수 있다. 취소는 계정을 ACTIVE로 돌리고 탈퇴 기간에 발급된 세션은 복구하지 않으므로 회원은 다시 로그인한다. 예약 시각에 도달하면 취소할 수 없으며 회원 레코드, OAuth 연결, 사용자 콘텐츠와 저장 미디어를 운영 데이터베이스 및 R2에서 물리 삭제한다. 법령상 보관하는 관리자 접속기록은 회원 콘텐츠와 분리해 SECURITY.md 정책에 따라 보관한다.

일반 Naver 로그인 시 탈퇴 대기 회원이 확인되면 `WITHDRAWAL_PENDING` 오류를 반환하며, 로그인으로 탈퇴 예약을 자동 취소하지 않는다. 취소 endpoint 요청은 `{authorizationCode, state}`를 받으며 로그인과 같은 일회용 state를 검증한다. 성공 응답은 200과 `{ "data": { "status": "ACTIVE" } }`를 반환하며 토큰을 발급하지 않는다.

### 6.3 Post

Post 생성 요청과 수정 가능한 필드:

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
  "boardId": "721389012345678904",
  "projectId": null,
  "visibilityStatus": "PUBLIC"
}
~~~

- title은 필수, 최대 200자다. summary는 nullable이며 최대 500자다.
- blocks는 순서 있는 배열이다. 최소 한 개를 보낸다. type은 TEXT 또는 CODE다.
- CODE 블록은 language가 필수이며 title은 선택이다. DB 제한은 content 최대 50,000자, title 최대 100자, language 최대 50자다.
- API 언어 값은 JAVA, JAVASCRIPT, TYPESCRIPT, PYTHON, HTML, CSS, SQL, JSON, YAML, MARKDOWN, BASH, SHELL이다.
- categoryId는 null 또는 최하위 Category 하나다. 신규 연결은 ACTIVE Category만 허용한다.
- tagIds는 중복 없는 ID 배열이며 신규 연결은 ACTIVE Tag만 허용한다.
- boardId와 projectId는 생략하거나 null로 둘 수 있다. 지정 시 작성자 본인 소유인지 검증한다.
- visibilityStatus는 PUBLIC 또는 HIDDEN이다. DELETED는 DELETE 동작으로만 설정한다.
- isBlocked, blockedAt, blockedByAdminId는 응답 전용이며 요청에 포함하면 400이다.
- PATCH에서 blocks, tagIds는 전체 교체다. categoryId, boardId, projectId, summary는 null로 지정해 연결·값을 제거할 수 있다.

Post 응답은 다음 정보를 제공한다. 목록에서는 blocks와 전체 상세 필드를 생략할 수 있다.

~~~json
{
  "data": {
    "id": "721389012345678901",
    "author": {
      "id": "721389012345678902",
      "nickname": "pebble",
      "profileImageUrl": null
    },
    "title": "Spring Security 설정",
    "summary": "인증 필터 체인을 구성한 기록",
    "blocks": [],
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
    "updatedAt": "2026-09-30T03:00:00Z"
  }
}
~~~

작성자 관리 응답은 추가로 isBlocked를 포함한다. 관리자는 isBlocked, blockedAt, blockedByAdminId를 확인할 수 있다. Guest와 다른 회원에게는 차단 메타데이터를 반환하지 않는다.

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
- visibilityStatus는 PUBLIC 또는 HIDDEN이다. isBlocked는 작성자가 변경할 수 없다.
- PATCH에서 features, links, tagIds를 보내면 기존 전체 항목을 교체한다.

Project 상세에는 미디어 배열, 주요 기능, 링크, 기술 태그, 좋아요 수, likedByMe를 포함한다. 목록 응답은 상세 설명과 하위 배열을 생략할 수 있다. 미디어 저장 키는 공개하지 않고, 권한을 통과한 요청에 한해 15분 만료의 signed URL을 반환한다. 만료된 URL로 아직 로드되지 않은 이미지를 요청하면 리소스 조회 API에서 새 URL을 받아 다시 요청한다. 이미 로드된 이미지는 URL 만료만으로 화면에서 사라지지 않는다.

### 6.5 관리자 차단

차단 API는 별도 요청 본문 없이 상태 변경을 수행한다.

- PUT block은 isBlocked=true, blockedAt=현재 시각, blockedByAdminId=요청 관리자 ID로 설정한다.
- DELETE block은 isBlocked=false로 설정하고 blockedAt과 blockedByAdminId를 비운다.
- 이미 차단된 리소스에 PUT, 차단되지 않은 리소스에 DELETE를 보내도 멱등하게 200을 반환한다.
- DELETED 콘텐츠는 차단 상태 변경을 거부하고 CONTENT_DELETED를 반환한다.
- 차단과 차단 해제는 visibilityStatus를 변경하지 않는다.
- 차단 메타데이터는 현재 차단 상태만 기록한다. 과거 차단 이력은 MVP에서 제공하지 않는다.

### 6.6 Category, Tag와 Board

Category 응답 항목: `{id, parentId, name, slug, displayOrder, status, children}`. name 최대 50자, slug 최대 100자다. 최대 깊이는 2단계이며 Post에는 최하위 Category만 지정한다.

`GET /categories`의 data는 최상위 Category 배열이고 각 children은 하위 Category 배열이다. `GET /tags`의 data는 Tag 배열이다. 빈 결과는 `[]`이며 ID·parentId는 문자열, 최상위 parentId는 null, 하위가 없는 children은 `[]`로 반환한다. Category 형제와 Tag 목록은 displayOrder 오름차순, 같은 순서는 숫자 ID 오름차순으로 정렬한다.

Tag 응답 항목: `{id, name, slug, displayOrder, status}`. name 최대 50자, slug 최대 100자다. 비활성 항목은 기존 공개 콘텐츠에서 참조되는 경우 탐색 결과에 표시될 수 있다.

Category 트리는 활성 하위의 경로를 보존하기 위해 비활성 상위를 `INACTIVE` 상태인 그룹으로 포함할 수 있다. 이 그룹은 신규 선택 대상이 아니다. 사용 중이지 않은 비활성 하위와 Tag는 제외한다. 신규 Post 연결은 ACTIVE이고 저장된 하위 Category가 없는 항목만 허용하며, 공개 children이 비었다는 이유만으로 최하위라고 판단하지 않는다.

초기 데이터는 상위 주제 Backend·Frontend·Database·DevOps·Architecture와 기술 Tag Java·Spring Boot·JPA·JavaScript·React·HTML·CSS·PostgreSQL이다. 하위 주제는 관리자 관리 API에서 필요에 따라 구성한다. 현재 분류 기반 단계에는 Post·Project 참조가 없으므로 활성 항목과 활성 하위의 상위 그룹을 조회한다. 콘텐츠 구현 시 공개 콘텐츠에서 사용 중인 비활성 항목의 탐색을 함께 연결한다.

Category 생성 요청은 `{parentId, name, slug, displayOrder}`이며 Tag 생성 요청은 `{name, slug, displayOrder}`다. 수정 요청은 각 생성 필드와 status를 부분 변경한다. status 값은 ACTIVE 또는 INACTIVE다. 참조 항목을 물리 삭제하는 API는 없다.

공개 Board 응답은 `{id, name, displayOrder, children}` 트리다. Board는 최대 3단계이며 공개 조회에서 해당 작성자의 PUBLIC이고 차단되지 않은 Post만 보인다. 자신의 Board API에는 숨김 또는 차단된 Post가 노출되지 않으며 Post 자체 조회 권한에 따르도록 한다.

Board 응답 항목은 `{id, parentId, name, displayOrder, children}`다. Board 생성 요청은 `{name, parentId, displayOrder}`다. name은 최대 50자다. 수정은 이 세 필드를 부분 변경한다. 부모 변경 시 소유자 일치, 최대 깊이, 순환 참조, 형제 이름 중복을 검증한다. 하위 Board가 있는 경우 삭제 요청은 RESOURCE_HAS_CHILDREN을 반환한다. 성공한 Board 삭제는 연결된 Post의 boardId를 null로 만든다.

### 6.7 댓글과 좋아요

댓글 생성 요청:

~~~json
{
  "body": "도움이 되는 글이네요.",
  "visibility": "PUBLIC"
}
~~~

- body는 필수, 최대 2,000자다.
- visibility는 PUBLIC 또는 SECRET이다.
- 작성, 좋아요는 PUBLIC이고 차단되지 않은 콘텐츠에서만 허용한다.
- Guest는 PUBLIC 댓글만 조회하며, 대상 콘텐츠도 PUBLIC이고 차단되지 않아야 한다.
- SECRET 댓글은 댓글 작성자, 대상 콘텐츠 작성자, MANAGER와 MASTER만 조회할 수 있다.
- USER는 본인 댓글만 수정·삭제한다. 작성자는 자신의 콘텐츠에 달린 SECRET 댓글을 읽을 수 있지만 수정·삭제할 수 없다.
- MANAGER와 MASTER는 운영 목록에서 모든 댓글을 조회하고 삭제할 수 있다. 댓글 수정은 불가하다.
- 대상 콘텐츠가 HIDDEN 또는 차단 상태이면 Guest와 일반 회원은 댓글 목록을 조회할 수 없다. 콘텐츠 작성자와 관리자는 관리 조회를 할 수 있다. SECRET 댓글 작성자는 단일 댓글 조회 경로에서 자신의 댓글을 읽을 수 있다.
- 공개 댓글은 대상 콘텐츠가 PUBLIC이고 차단되지 않은 경우에만 Guest에게 노출한다. SECRET 댓글의 접근 권한은 대상 콘텐츠 상태와 별도로 댓글 작성자, 콘텐츠 작성자, MANAGER, MASTER에게 부여한다.
- DELETED 대상 콘텐츠의 댓글은 관리자 운영 경로 외에는 반환하지 않는다.
- 삭제 댓글 본문은 어떤 응답에도 포함하지 않는다.

댓글 응답 항목은 `{id, author, body, visibility, createdAt, updatedAt}`다. 관리자는 삭제된 댓글의 운영 메타데이터를 볼 수 있지만 삭제 본문을 복구할 수 없다.

좋아요 등록/취소 요청은 본문이 없다. 공개 가능한 Post/Project 상세 응답에 likeCount를 포함한다. 인증된 USER에게 likedByMe를 함께 반환한다. Guest에는 false를 반환한다. MANAGER와 MASTER의 likedByMe는 false이며 좋아요 API 호출은 403이다.

### 6.8 미디어

업로드는 multipart/form-data를 사용한다. 원본 파일은 처리 중에만 사용하고 R2에는 WebP 파생 파일만 저장한다.

- Post 썸네일 업로드 필드: file
- Project 미디어 업로드 필드: file, mediaRole, 선택적 altText, displayOrder
- mediaRole은 THUMBNAIL 또는 SCREENSHOT이다. Project당 THUMBNAIL은 하나만 허용한다.
- 업로드 성공 응답은 미디어 ID, role, altText, displayOrder, 접근 가능한 signed URL을 반환한다. Project 미디어 응답 data는 `{id, mediaRole, altText, displayOrder, url, thumbnailUrl, createdAt}`다. Post 썸네일 업로드 응답 data는 `{thumbnailUrl}`이다.
- 입력은 정적 JPEG(.jpg, .jpeg), PNG, WebP만 허용한다. SVG, GIF와 애니메이션 이미지는 지원하지 않는다. 파일당 원본 크기는 최대 10 MiB, 디코딩한 이미지 총 픽셀은 최대 20,000,000, 가로와 세로 각각 최대 8,000 px다. MIME 헤더만 신뢰하지 않고 실제 파일 서명과 디코더 결과를 검증한다.
- 입력 이미지의 종횡비를 유지하고 확대하지 않는다. 화면 표시용 파생 이미지는 긴 변 최대 2,560 px, 목록용 썸네일은 긴 변 최대 480 px로 만든다. 두 파생물 모두 WebP lossy quality 82로 저장하며 EXIF 등 불필요한 메타데이터를 제거한다. 원본은 저장하지 않는다.
- 모든 입력에 WebP lossy quality 82를 일괄 적용한다. 손실 압축은 파일 크기를 줄이는 대신 일부 시각 정보를 버리므로 작은 글자나 얇은 선이 많은 PNG 스크린샷·도식은 경계가 부드러워지거나 압축 흔적이 보일 수 있다. 이는 MVP의 저장 효율을 위한 품질 절충이며 원본은 보관하지 않는다.
- Post의 `thumbnailUrl`은 480 px 썸네일이다. Project의 `url`은 화면 표시용 이미지이고 `thumbnailUrl`은 목록과 미리보기용 파생 이미지다. R2에는 비공개 버킷과 서버 생성 키를 사용한다.
- signed GET URL은 발급 시점부터 15분 유효하다. 콘텐츠를 숨김·차단·삭제하거나 회원이 탈퇴 예약한 뒤 새 URL을 발급하지 않는다. 이미 발급된 URL은 만료 전까지 사용할 수 있다. 화면에 이미 내려받은 이미지는 URL 만료 시점에 사라지지 않으며, 만료 후 새로 필요한 이미지는 권한을 다시 확인한 리소스 조회로 URL을 갱신한다.

## 7. 목록·검색 query parameter

| Resource | 지원 필터 |
|---|---|
| GET /posts | categoryId, tagId, boardId, projectId, authorId |
| GET /posts/search | q 필수, categoryId, tagId |
| GET /projects | tagId, lifecycleStatus |
| GET /projects/search | q 필수, tagId, lifecycleStatus |
| GET /members/{memberId}/posts | categoryId, tagId, boardId |
| GET /members/{memberId}/projects | tagId, lifecycleStatus |
| GET /members/me/posts | visibilityStatus, boardId |
| GET /members/me/projects | visibilityStatus |
| GET /admin/posts | q, visibilityStatus, isBlocked, authorId |
| GET /admin/projects | q, visibilityStatus, isBlocked, ownerId |
| GET /admin/members | status, q |
| GET /admin/post-comments | postId, authorId, visibility, deleted |
| GET /admin/project-comments | projectId, authorId, visibility, deleted |

- q는 앞뒤 공백을 제거한 비어 있지 않은 검색어다.
- Post 검색 대상은 제목, 본문, Category와 Tag다.
- Project 검색 대상은 이름, 소개, 상세 설명, 기술 스택이다.
- Guest 검색은 PUBLIC이고 차단되지 않은 콘텐츠만 대상으로 한다.
- 검색은 MVP의 기본 문자열 검색을 제공한다. 자동 완성, 검색어 추천, 개인화, 전문 검색 엔진은 제공하지 않는다.
- USER 요청의 visibilityStatus 필터는 본인 콘텐츠 목록에서만 사용할 수 있다. 공개 사용자 목록의 필터는 PUBLIC/차단 제외를 우회하지 않는다.
- 검색 결과 및 댓글·좋아요 집계는 차단된 콘텐츠를 포함하지 않는다.

## 8. API 전역 제약

- API는 회원이 다른 회원의 Post, Project, Board, 댓글을 수정 또는 삭제하지 못하게 한다.
- isBlocked, blockedAt, blockedByAdminId는 Post·Project 응답에서 권한에 따라 읽을 수 있지만 USER 생성·수정 요청으로 변경할 수 없다.
- 관리자 강제 삭제는 visibilityStatus를 DELETED로 전환한다. DELETED 복구 API는 없다.
- 탈퇴 요청 후 7일 동안 취소할 수 있으며, 요청 즉시 계정 이용과 콘텐츠 공개를 막는다. 예약 기간이 끝나면 계정과 콘텐츠를 영구 삭제한다.
- 업로드 형식, 크기·픽셀 한도, WebP 파생 크기와 품질, signed GET URL 15분 만료 정책은 6.8절을 따른다.
