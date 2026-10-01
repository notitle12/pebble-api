# Pebble API - Database Specification

이 문서는 Pebble MVP의 주요 테이블, 관계, 제약조건 및 인덱스 기준을 정의한다.

제품 요구사항은 `PRD.md`, Feature 책임은 `ARCHITECTURE.md`, API 계약은 `API.md`, 인증 세부 정책은 `SECURITY.md`를 따른다.

---

## 1. 설계 범위와 공통 규칙

### 1.1 설계 범위

이 문서는 회원, 관리자, Post, Project, 카테고리, 기술 태그, 개인 게시판, 댓글 및 좋아요의 PostgreSQL 기준 테이블 명세를 정의한다.

Pebble의 DBMS는 PostgreSQL이다. 이 문서의 컬럼 타입과 문자열 길이가 기준이며, API 입력 검증은 이 제한을 그대로 적용한다. 길이가 제한된 문자열은 `VARCHAR(n)`으로, 긴 본문은 `TEXT`와 `CHECK (char_length(column) <= n)`으로 제한한다. PostgreSQL의 `char_length` 기준으로 제한하며, API도 동일한 문자 수 기준을 사용한다.

관리자 접속·감사기록은 회원과 콘텐츠 테이블에 저장하지 않고 별도 접근 제한 저장소에 보관한다. 보유기간은 `SECURITY.md` 10절을 따른다.

인증 Refresh Token 세션 상태는 PostgreSQL 테이블에 저장하지 않고 Redis에서 관리한다. Redis 저장 항목과 TTL은 `SECURITY.md`를 따른다. Token 회전 뒤에도 세션 최초 로그인 시각과 절대 만료 시각은 Redis에서 유지해야 하며, 이 인증 정책 때문에 PostgreSQL 테이블이나 컬럼을 추가하지 않는다.

### 1.2 공통 규칙

- 식별자는 Architecture 문서의 TSID 정책을 사용하고 PostgreSQL `BIGINT`로 저장한다. DB sequence나 identity column을 사용하지 않는다.
- 시각 컬럼은 PostgreSQL `TIMESTAMPTZ`로 저장하고 애플리케이션에서 UTC 기준으로 다룬다. 날짜만 필요한 컬럼은 `DATE`를 사용한다.
- 도메인 Enum 값은 PostgreSQL native `ENUM` 대신 `VARCHAR`와 `CHECK` 제약으로 저장한다. 허용 값은 각 컬럼 표에 표시한다.
- 기본 생성·수정 시각이 필요한 테이블은 `created_at`, `updated_at`을 가진다.
- 상태 값은 아래에 정의한 값만 허용한다. 구현에서는 프로젝트의 기존 Enum 및 검증 규칙을 따른다.
- Post와 Project의 `DELETED`는 논리 삭제 상태로 취급한다. 계정 탈퇴에 따른 보존 및 최종 파기 정책은 10절을 따른다.
- 파일 바이너리는 DB에 저장하지 않는다. 이미지 파일은 업로드 시 서버에서 검증·재인코딩한 WebP만 저장하고, DB에는 저장 키와 메타데이터만 둔다.
- FK 삭제 동작은 명시적으로 정하지 않은 경우 자동 연쇄 삭제하지 않는다. 연관 데이터 변경은 해당 Feature의 유스케이스에서 처리한다.
- FK의 기본 삭제 동작은 `ON DELETE RESTRICT`로 둔다. 의도적인 연쇄 삭제가 필요한 관계만 개별적으로 명시한다.

---

## 2. 주요 관계

```text
Member 1 ── N Post
Member 1 ── N Project
Member 1 ── N Board
Board  1 ── N Board (parent-child)
Board  0..1 ── N Post
Category 0..1 ── N Post
Tag N ── N Post
Tag N ── N Project (기술 스택)
Project 0..1 ── 0..N Post (Post는 Project를 0개 또는 1개 참조)
Project 1 ── N ProjectFeature
Post 1 ── N PostComment
Project 1 ── N ProjectComment
Post 1 ── N PostLike
Project 1 ── N ProjectLike
Member 1 ── N Comment / Like
```

Post와 Project는 서로 다른 Feature와 테이블을 유지한다. 댓글과 좋아요도 대상 종류별 테이블을 사용하여 대상 FK를 직접 둔다.

---

## 3. 계정 테이블

### 3.1 `member`

일반 회원과 회원 상태를 저장한다.

| 컬럼 | PostgreSQL 타입 | NULL | 규칙 |
|---|---|---:|---|
| `id` | BIGINT | N | PK, TSID |
| `nickname` | VARCHAR(30) | N | UNIQUE, 표시 이름 |
| `blog_name` | VARCHAR(100) | Y | UNIQUE, 최초 설정 전 NULL |
| `handle` | VARCHAR(30) | Y | UNIQUE, 최초 설정 전 NULL; 이후 변경 불가 |
| `profile_completed_at` | TIMESTAMPTZ | Y | 최초 설정 완료 시각 |
| `nickname_changed_at` | TIMESTAMPTZ | Y | 닉네임 마지막 설정/변경 시각 |
| `blog_name_changed_at` | TIMESTAMPTZ | Y | 블로그명 마지막 설정/변경 시각 |
| `profile_image_url` | VARCHAR(2048) | Y | 프로필 이미지 URL |
| `status` | VARCHAR(20) | N | CHECK: `ACTIVE`, `SUSPENDED`, `WITHDRAWAL_PENDING` |
| `withdrawal_requested_at` | TIMESTAMPTZ | Y | 탈퇴 예약 시각 |
| `withdrawal_scheduled_at` | TIMESTAMPTZ | Y | 탈퇴 데이터 파기 예정 시각; 요청 시각 + 7일 |
| `created_at` | TIMESTAMPTZ | N | 생성 시각 |
| `updated_at` | TIMESTAMPTZ | N | 수정 시각 |

- CHECK로 `status = 'WITHDRAWAL_PENDING'`일 때 두 탈퇴 시각이 모두 존재하고, 그 외 상태에서는 두 값이 모두 NULL임을 보장한다. 탈퇴 예약 취소 시 시각을 NULL로 되돌린다. 탈퇴 유예 기간이 끝나면 `WITHDRAWN` tombstone을 남기지 않고 회원 및 종속 데이터를 물리 삭제한다.

`V4__add_member_profile.sql`은 최초 설정 컬럼과 세 이름의 고유 제약을 추가한다. 기존 닉네임 중복은 created_at·id 순서의 첫 회원 값을 보존하고 나머지에 30자 이내 숫자 접미사를 부여한다. 기존 접미사 이름도 먼저 예약해 덮어쓰지 않으며 회원 ID·OAuth 연결은 유지한다. 기존 회원의 blog_name·handle·완료 시각은 NULL로 두어 다음 로그인에서 설정한다. 닉네임 변경 시각은 created_at으로 채우며 최초 설정 때 두 쿨타임을 새로 시작한다.

handle은 영문으로 시작하는 3~30자 영문 소문자·숫자·하이픈이며 끝 하이픈 및 API 예약어를 CHECK로 막는다. profile_completed_at이 없으면 blog_name·handle·blog_name_changed_at은 모두 NULL이어야 하고, 완료되면 이름과 변경 시각이 있어야 한다. 최초 handle 확정 뒤 변경 또는 NULL 제거는 DB 트리거도 거부한다. 닉네임 기본값 할당과 직접 이름 설정은 PostgreSQL advisory transaction lock을 공유하고 고유 제약을 최종 방어로 사용한다. 쿨타임은 Redis TTL 대신 회원 변경 시각으로 계산하고 회원 행 배타 잠금 안에서 검증한다.

### 3.2 `member_oauth_identity`

일반 회원의 OAuth 식별자를 저장한다. OAuth 토큰이나 비밀 정보는 저장하지 않는다.

| 컬럼 | PostgreSQL 타입 | NULL | 규칙 |
|---|---|---:|---|
| `id` | BIGINT | N | PK, TSID |
| `member_id` | BIGINT | N | FK → `member.id` |
| `provider` | VARCHAR(20) | N | MVP 허용 값: `NAVER` |
| `provider_subject` | VARCHAR(255) | N | OAuth 공급자가 제공하는 고유 회원 식별자 |
| `created_at` | TIMESTAMPTZ | N | 연결 시각 |

- UNIQUE (`provider`, `provider_subject`)
- UNIQUE (`member_id`, `provider`)
- MVP에서는 회원당 Provider별 OAuth 식별자 하나를 허용한다.
- 여러 OAuth 계정 연결 정책은 추가 소셜 로그인을 도입할 때 재검토한다.

### 3.3 `admin_account`

일반 회원과 분리된 관리자 계정을 저장한다.

| 컬럼 | PostgreSQL 타입 | NULL | 규칙 |
|---|---|---:|---|
| `id` | BIGINT | N | PK, TSID |
| `login_id` | VARCHAR(100) | N | UNIQUE |
| `password_hash` | VARCHAR(255) | N | 해시된 비밀번호만 저장 |
| `role` | VARCHAR(20) | N | CHECK: `MANAGER`, `MASTER` |
| `status` | VARCHAR(20) | N | CHECK: `ACTIVE`, `INACTIVE` |
| `created_at` | TIMESTAMPTZ | N | 생성 시각 |
| `updated_at` | TIMESTAMPTZ | N | 수정 시각 |

비밀번호 정책, 관리자 로그인 및 토큰 세부사항은 `SECURITY.md`에서 정의한다.

---

## 4. 공통 분류 테이블

### 4.1 `category`

관리자가 관리하는 Post 공통 주제 분류다. 계층은 최대 2단계이며, Java·Spring 같은 구체적인 기술은 Category가 아니라 Tag로 관리한다.

| 컬럼 | PostgreSQL 타입 | NULL | 규칙 |
|---|---|---:|---|
| `id` | BIGINT | N | PK, TSID |
| `parent_id` | BIGINT | Y | FK → `category.id`; 최상위 분류는 NULL |
| `name` | VARCHAR(50) | N | 표시 이름 |
| `slug` | VARCHAR(100) | N | UNIQUE, URL 및 검색용 정규화 값 |
| `display_order` | INTEGER | N | 형제 분류 내 정렬 순서 |
| `status` | VARCHAR(20) | N | CHECK: `ACTIVE`, `INACTIVE` |
| `created_at` | TIMESTAMPTZ | N | 생성 시각 |
| `updated_at` | TIMESTAMPTZ | N | 수정 시각 |

- 최대 깊이 2와 순환 참조 방지는 애플리케이션에서 검증한다.
- Post에는 최하위 Category만 연결한다. 하위 Category가 있는 부모는 탐색용 그룹이다.
- 비활성 Category는 신규 지정에서 제외하고 선택 목록에서 숨긴다. 기존 Post 연결·표시는 유지하며, 공개 Post는 비활성 Category 기준 탐색 결과에도 계속 포함한다.
- 참조 중인 Category를 물리 삭제하지 않는다.

현재 저장 기반은 `V2__create_category_and_tag_tables.sql`이며 `V3__seed_initial_categories_and_tags.sql`이 주제 Category 5개와 기술 Tag 8개를 ACTIVE 상태로 초기 등록한다. 최대 깊이는 Category 생성 모델에서 2단계로 제한한다. 이후 관리자 이동·수정 기능에서도 최대 깊이와 순환을 검증해야 한다. Post의 최하위 판정은 비활성 하위도 포함한 저장 구조를 기준으로 하며, 참조 중인 최상위 Category에 하위를 추가할 때는 기존 Post의 최하위 분류 규칙을 함께 유지해야 한다. Post는 최하위 Category만 새로 지정할 수 있으며, 기존 연결이 비활성화되어도 공개 분류 조회에서 사용 중인 Post를 계속 탐색한다.

### 4.2 `tag`

Post 기술 태그와 Project 기술 스택에서 공유하는 관리자 관리 기술 어휘다.

| 컬럼 | PostgreSQL 타입 | NULL | 규칙 |
|---|---|---:|---|
| `id` | BIGINT | N | PK, TSID |
| `name` | VARCHAR(50) | N | 표시 이름 |
| `slug` | VARCHAR(100) | N | UNIQUE, 정규화 값 |
| `display_order` | INTEGER | N | 선택 UI 정렬 순서 |
| `status` | VARCHAR(20) | N | CHECK: `ACTIVE`, `INACTIVE` |
| `created_at` | TIMESTAMPTZ | N | 생성 시각 |
| `updated_at` | TIMESTAMPTZ | N | 수정 시각 |

- 사용자는 활성 Tag 중에서 선택한다. MVP에서는 임의 Tag 생성 기능을 제공하지 않는다.
- Post와 Project에서 동일한 Tag를 사용할 수 있다.
- 비활성 Tag는 신규 지정에서 제외하고 선택 목록에서 숨긴다. 기존 Post·Project 연결·표시는 유지하며, 공개 콘텐츠는 비활성 Tag 기준 탐색 결과에도 계속 포함한다.
- Tag는 프로젝트의 기술 어휘를 소유하고, Post나 Project의 본문 및 상태는 소유하지 않는다.

---

## 5. 개인 블로그 게시판

### 5.1 `board`

회원 소유의 개인 게시판 트리를 저장한다. 서비스 공통 Category와 별도 테이블로 관리한다.

Board 테이블과 Post의 `board_id`는 V7에서 생성한다. Board는 회원이 소유하는 최대 3단계 트리이며 Post 연결은 작성자 소유 Board로 제한한다.

| 컬럼 | PostgreSQL 타입 | NULL | 규칙 |
|---|---|---:|---|
| `id` | BIGINT | N | PK, TSID |
| `owner_member_id` | BIGINT | N | FK → `member.id` |
| `parent_id` | BIGINT | Y | 복합 FK의 일부 → 같은 소유자의 `board.id`; 최상위는 NULL |
| `name` | VARCHAR(50) | N | 게시판 이름 |
| `display_order` | INTEGER | N | 형제 게시판 내 정렬 순서 |
| `deleted_at` | TIMESTAMPTZ | Y | Board 삭제 시각. 논리 삭제 |
| `created_at` | TIMESTAMPTZ | N | 생성 시각 |
| `updated_at` | TIMESTAMPTZ | N | 수정 시각 |

- UNIQUE (`id`, `owner_member_id`)를 두어 복합 FK로 소유자를 검증할 수 있게 한다.
- 복합 FK (`parent_id`, `owner_member_id`) → `board(id`, `owner_member_id)`로 부모 게시판도 같은 회원 소유임을 보장한다. 루트 게시판은 `parent_id = NULL`이므로 이 FK 검증에서 제외된다.
- 최대 깊이 3, 순환 참조 방지, 같은 부모 아래 대소문자 구분 이름 중복 방지는 애플리케이션에서 검증한다. 이름은 공백이 아닌 최대 50 유니코드 코드 포인트다. 논리 삭제된 Board의 이름은 재사용할 수 있다. display_order는 0 이상이며 형제 정렬 키다. 같은 순서에서는 숫자 ID 오름차순으로 조회한다.
- Board 삭제 시 하위 미삭제 Board가 없어야 한다. 연결된 모든 Post(논리 삭제된 Post 포함)의 `board_id`를 NULL로 변경하고 Board에 `deleted_at`을 설정한다. Post Feature의 애플리케이션 계약을 통해 한 트랜잭션으로 수행한다.
- Board 자체의 공개 상태는 두지 않는다. 공개 트리는 작성자 회원이 조회 가능한 경우 반환하며 게시판별 Post 조회는 지정 Board에 직접 연결된 PUBLIC·비차단 Post만 반환한다. 하위 Board Post는 포함하지 않는다. 탈퇴 대기 회원은 공개 조회에서 404이며 정지 회원의 Board와 콘텐츠는 공개 정책에 따라 조회할 수 있다.

---

## 6. Post 테이블

### 6.1 `post`

기술 블로그 게시글과 소유·분류 관계를 저장한다.

| 컬럼 | PostgreSQL 타입 | NULL | 규칙 |
|---|---|---:|---|
| `id` | BIGINT | N | PK, TSID |
| `author_member_id` | BIGINT | N | FK → `member.id` |
| `board_id` | BIGINT | Y | 복합 FK의 일부 → 같은 작성자 소유 `board.id` |
| `post_number` | BIGINT | N | 작성자별 공개 주소 번호, 1부터 증가; 작성자와 복합 UNIQUE, 0보다 큼 |
| `category_id` | BIGINT | Y | FK → `category.id`; 최하위 분류만 허용 |
| `title` | VARCHAR(200) | N | 제목 |
| `slug` | VARCHAR(200) | Y | 작성자 블로그 주소 키; 작성자와 복합 UNIQUE |
| `display_order` | INTEGER | N | 작성자 미삭제 Post 목록 내 위치, 0 이상 |
| `summary` | TEXT | Y | 목록/검색용 요약 또는 작성자 지정 설명; `CHECK (summary IS NULL OR char_length(summary) <= 500)` |
| `visibility_status` | VARCHAR(20) | N | CHECK: `PUBLIC`, `HIDDEN`, `DELETED` |
| `is_blocked` | BOOLEAN | N | DEFAULT FALSE; 관리자 차단 여부. visibility_status와 독립 |
| `blocked_at` | TIMESTAMPTZ | Y | 현재 차단을 설정한 시각 |
| `blocked_by_admin_id` | BIGINT | Y | 현재 차단을 설정한 관리자 ID; 관리자 FK·운영 기능은 아직 미구현 |
| `published_at` | TIMESTAMPTZ | Y | 최초 공개 시각 |
| `deleted_at` | TIMESTAMPTZ | Y | `DELETED` 전환 시각 |
| `created_at` | TIMESTAMPTZ | N | 생성 시각 |
| `updated_at` | TIMESTAMPTZ | N | 수정 시각 |

- Post 테이블과 본문·Tag 연결은 V5에서 생성하고 V6에서 `slug`, `display_order`, `post_number`를 추가한다. 내부 `id`는 TSID이며 공개 번호와 별개다.
- 생성은 작성자 Member 행을 잠근 뒤 해당 작성자의 기존 최대 post_number에 1을 더한다. 논리 삭제된 Post도 번호 할당 기준에 포함하므로 번호는 재사용하지 않는다. DB는 CHECK (`post_number > 0`)와 UNIQUE (`author_member_id`, `post_number`)를 적용한다.
- `slug`는 null 또는 작성자별 고유 값이며 UNIQUE (`author_member_id`, `slug`)로 제한한다. 애플리케이션은 최대 200자의 소문자 영문·숫자·하이픈만 허용하고 숫자 전용 값과 `search`를 거부한다. 중복이면 `-2`, `-3` 접미사를 붙이며 논리 삭제 후에도 기존 slug를 예약한다.
- `display_order`는 작성자의 DELETED가 아닌 Post 목록에서 0부터 시작한다. 생성 기본값은 0이며 이동 시 재정렬하고 논리 삭제 뒤 남은 항목을 압축한다. 복합 UNIQUE는 두지 않는다.
- Post와 Board의 쓰기는 ACTIVE 상태 검증 뒤 작성자 Member 행을 비관적 쓰기 잠금해 같은 회원의 콘텐츠·Board 변경을 직렬화한다. Post 생성에서는 이 잠금으로 slug 및 번호 할당도 직렬화한다. 수정·삭제는 소유권을 검증하고 대상 Post 행을 잠근다. Post 쓰기는 프로필 설정을 완료해야 하며 Board 쓰기는 프로필 완료를 요구하지 않는다.
- `category_id`가 설정되면 Category가 최하위인지 애플리케이션에서 검증한다.
- 기존 Post가 사용 중인 비활성 Category 연결은 보존하고 공개 조회에서 계속 포함한다. 부모 Category 조회는 해당 하위 Category의 공개 Post를 포함한다.
- CHECK 제약으로 is_blocked = TRUE이면 blocked_at과 blocked_by_admin_id가 모두 존재하고, FALSE이면 둘 다 NULL이 되도록 한다. 차단 해제 시 현재 차단 메타데이터를 비운다.
- 공개 조회는 visibility_status = PUBLIC AND is_blocked = FALSE인 Post만 대상으로 한다. Post 검색은 미구현이다.
- is_blocked는 작성자가 변경할 수 없다. MANAGER 또는 MASTER 차단·차단 해제 기능은 미구현이다.
- Post 본문과 코드 블록은 `post_block`에 순서대로 저장한다.
- Board 연결은 V7에서 복합 FK (`board_id`, `author_member_id`) → `board(id, owner_member_id)`를 적용해 타인 게시판 배치를 차단한다. 공개 게시판 목록은 board_id와 작성자 상태를 기준으로 직접 연결된 공개 Post를 조회한다. Project 참조 FK와 썸네일 키는 아직 없다. Project 연결도 복합 FK로 본인 Project만 참조할 계획이며 대상 테이블에 복합 UNIQUE를 둔다. 관리자 계정 구현 시 `blocked_by_admin_id`의 실제 FK도 추가한다.
- Post 쓰기와 순서 변경은 ACTIVE이며 프로필 설정을 완료한 작성자만 수행한다.
- 일반 DELETE는 visibility_status를 DELETED로 바꾸는 논리 삭제다. 단일 Post 물리 삭제 API는 없으며, 물리 파기 시 post_block·post_tag의 Post FK CASCADE에 따라 하위 행도 함께 정리된다. 계정 탈퇴에 따른 Post 물리 파기와 이 시점의 주소 예약 정리는 계정 데이터 보존·파기 절차에서 다룬다.

### 6.2 `post_block`

Post 본문을 순서가 있는 텍스트·코드 블록으로 저장한다.

| 컬럼 | PostgreSQL 타입 | NULL | 규칙 |
|---|---|---:|---|
| `id` | BIGINT | N | PK, TSID |
| `post_id` | BIGINT | N | FK → `post.id` |
| `block_type` | VARCHAR(16) | N | CHECK: `TEXT`, `CODE` |
| `content` | TEXT | N | 텍스트 또는 코드 내용; `CHECK (char_length(content) <= 50000)` |
| `language` | VARCHAR(50) | Y | 코드 언어. `CODE` 블록에서 필수 |
| `title` | VARCHAR(100) | Y | 코드 블록 제목 또는 설명 |
| `display_order` | INTEGER | N | Post 내 블록 순서, 0 이상 |

- UNIQUE (`post_id`, `display_order`)
- `block_type = CODE`이면 `language`가 필수인 CHECK 제약을 둔다.
- Post를 물리 삭제할 때 해당 블록은 함께 삭제할 수 있다.

### 6.3 `post_tag`

Post와 기술 Tag의 다대다 연결 테이블이다.

| 컬럼 | PostgreSQL 타입 | NULL | 규칙 |
|---|---|---:|---|
| `post_id` | BIGINT | N | PK 일부, FK → `post.id` |
| `tag_id` | BIGINT | N | PK 일부, FK → `tag.id` |
| `created_at` | TIMESTAMPTZ | N | 연결 시각 |

- 복합 PK (`post_id`, `tag_id`)로 같은 Tag 중복 연결을 방지한다.
- 비활성 Tag를 신규 연결할 수 없다.

---

## 7. Project 테이블

### 7.1 `project`

한 명의 회원이 소유하는 개인 포트폴리오 Project 콘텐츠를 저장한다.

Project 스키마는 후속 기능의 목표다. 현재 Flyway에는 Project 테이블이 없으며 Post와 Project를 연결하는 FK도 없다.

| 컬럼 | PostgreSQL 타입 | NULL | 규칙 |
|---|---|---:|---|
| `id` | BIGINT | N | PK, TSID |
| `owner_member_id` | BIGINT | N | FK → `member.id` |
| `name` | VARCHAR(120) | N | 프로젝트명 |
| `summary` | TEXT | Y | 목록 및 검색용 소개; `CHECK (summary IS NULL OR char_length(summary) <= 500)` |
| `description` | TEXT | Y | 상세 설명; `CHECK (description IS NULL OR char_length(description) <= 20000)` |
| `architecture_description` | TEXT | Y | 아키텍처 설명; `CHECK (architecture_description IS NULL OR char_length(architecture_description) <= 20000)` |
| `execution_instructions` | TEXT | Y | 실행 방법; `CHECK (execution_instructions IS NULL OR char_length(execution_instructions) <= 10000)` |
| `lifecycle_status` | VARCHAR(20) | N | CHECK: `IN_PROGRESS`, `COMPLETED`; 작성 시 선택 |
| `started_on` | DATE | Y | 프로젝트 시작일 |
| `completed_on` | DATE | Y | 프로젝트 종료일 |
| `visibility_status` | VARCHAR(20) | N | CHECK: `PUBLIC`, `HIDDEN`, `DELETED` |
| `is_blocked` | BOOLEAN | N | DEFAULT FALSE; 관리자 차단 여부. visibility_status와 독립 |
| `blocked_at` | TIMESTAMPTZ | Y | 현재 차단을 설정한 시각 |
| `blocked_by_admin_id` | BIGINT | Y | FK → admin_account.id; 현재 차단을 설정한 관리자 |
| `published_at` | TIMESTAMPTZ | Y | 최초 공개 시각 |
| `deleted_at` | TIMESTAMPTZ | Y | `DELETED` 전환 시각 |
| `created_at` | TIMESTAMPTZ | N | 생성 시각 |
| `updated_at` | TIMESTAMPTZ | N | 수정 시각 |

- UNIQUE (`id`, `owner_member_id`)는 향후 Post와의 선택적 동일 작성자 연결을 위한 복합 FK에서 사용한다.
- Project의 진행 상태와 공개 상태는 서로 다른 값이며 하나의 상태 컬럼으로 합치지 않는다.
- CHECK 제약으로 is_blocked = TRUE이면 blocked_at과 blocked_by_admin_id가 모두 존재하고, FALSE이면 둘 다 NULL이 되도록 한다. 차단 해제 시 현재 차단 메타데이터를 비운다.
- 공개 조회와 검색은 visibility_status = PUBLIC AND is_blocked = FALSE인 Project만 대상으로 한다.
- is_blocked는 작성자가 변경할 수 없다. MANAGER 또는 MASTER의 차단·차단 해제 유스케이스만 변경한다.

### 7.1.1 `project_feature`

Project의 주요 기능을 순서가 있는 독립 항목으로 저장한다. Project 전체 소개/서술은 `project.description`에 두고, 개별 기능명과 설명은 이 테이블에 둔다.

| 컬럼 | PostgreSQL 타입 | NULL | 규칙 |
|---|---|---:|---|
| `id` | BIGINT | N | PK, TSID |
| `project_id` | BIGINT | N | FK → `project.id` |
| `title` | VARCHAR(100) | N | 기능명 |
| `description` | TEXT | N | 기능 설명; `CHECK (char_length(description) <= 2000)` |
| `display_order` | INTEGER | N | Project 내 표시 순서, 0 이상 |
| `created_at` | TIMESTAMPTZ | N | 생성 시각 |
| `updated_at` | TIMESTAMPTZ | N | 수정 시각 |

- UNIQUE (`project_id`, `display_order`)
- 항목의 생성·수정·삭제·순서는 Project 소유자가 관리한다.

### 7.2 `project_tag`

Project와 기술 스택 Tag의 다대다 연결 테이블이다.

| 컬럼 | PostgreSQL 타입 | NULL | 규칙 |
|---|---|---:|---|
| `project_id` | BIGINT | N | PK 일부, FK → `project.id` |
| `tag_id` | BIGINT | N | PK 일부, FK → `tag.id` |
| `display_order` | INTEGER | N | Project 상세의 기술 스택 표시 순서 |
| `created_at` | TIMESTAMPTZ | N | 연결 시각 |

- 복합 PK (`project_id`, `tag_id`)로 같은 기술의 중복 지정을 방지한다.
- UNIQUE (`project_id`, `display_order`)로 기술 스택 표시 순서를 고정한다.
- 비활성 Tag를 신규 연결할 수 없다.

### 7.3 `project_link`

Project에 연결된 GitHub·배포·다운로드 등 외부 링크를 저장한다.

| 컬럼 | PostgreSQL 타입 | NULL | 규칙 |
|---|---|---:|---|
| `id` | BIGINT | N | PK, TSID |
| `project_id` | BIGINT | N | FK → `project.id` |
| `link_type` | VARCHAR(20) | N | CHECK: `GITHUB`, `DEPLOYMENT`, `DOWNLOAD`, `OTHER` |
| `label` | VARCHAR(100) | Y | 사용자 지정 표시 이름 |
| `url` | VARCHAR(2048) | N | 외부 URL |
| `display_order` | INTEGER | N | Project 상세 표시 순서 |
| `created_at` | TIMESTAMPTZ | N | 생성 시각 |

링크 대상의 가용성과 수명은 외부 서비스가 결정한다. Pebble은 URL을 저장하고 표시하며 원본 저장소나 문서를 소유하지 않는다.

### 7.4 `project_media`

Project 대표 이미지 및 스크린샷의 저장소 참조를 보관한다.

| 컬럼 | PostgreSQL 타입 | NULL | 규칙 |
|---|---|---:|---|
| `id` | BIGINT | N | PK, TSID |
| `project_id` | BIGINT | N | FK → `project.id` |
| `media_role` | VARCHAR(20) | N | CHECK: `THUMBNAIL`, `SCREENSHOT` |
| `storage_key` | VARCHAR(512) | N | 화면 표시용 WebP 파생 파일의 저장소 키 |
| `thumbnail_storage_key` | VARCHAR(512) | N | 목록·미리보기용 480px WebP 파생 파일의 저장소 키 |
| `alt_text` | VARCHAR(300) | Y | 접근성 및 이미지 설명 |
| `display_order` | INTEGER | N | 표시 순서 |
| `created_at` | TIMESTAMPTZ | N | 생성 시각 |

Project당 `THUMBNAIL`은 최대 1개로 제한한다. PostgreSQL 부분 UNIQUE 인덱스로 보장한다: `UNIQUE (project_id) WHERE media_role = 'THUMBNAIL'`.

MVP 저장소는 Cloudflare R2를 우선 사용한다. 버킷은 비공개로 유지하며 콘텐츠 조회 권한을 확인한 API만 15분 만료의 presigned GET URL을 발급한다. 공개 버킷을 사용하면 HIDDEN 콘텐츠 이미지도 직접 노출될 수 있으므로 MVP에서는 허용하지 않는다. Presigned URL은 소지자가 만료 시각까지 사용할 수 있는 접근 권한이다. R2 presigned URL은 S3 API 도메인에서 사용하며 custom domain을 통한 CDN 공개 방식은 MVP 기본 정책에 포함하지 않는다.

업로드는 정적 JPEG(.jpg, .jpeg), PNG, WebP만 허용한다. 파일당 입력은 최대 10 MiB, 최대 20,000,000 픽셀, 가로·세로 각각 최대 8,000 px로 제한하고 실제 파일 서명과 디코더 결과를 검증한다. 입력 종횡비를 유지하고 확대하지 않는다. 화면 표시용 이미지는 긴 변 최대 2,560 px, 목록·미리보기용 썸네일은 긴 변 최대 480 px로 축소하고, 두 파생물 모두 WebP lossy quality 82로 인코딩한다. EXIF 등 불필요한 메타데이터를 제거한다. 원본은 처리 후 저장하지 않으며 R2에는 파생 파일만 둔다. Post 썸네일 키와 Project의 화면 표시·썸네일 키를 각각 저장한다.

WebP lossy quality 82를 모든 입력에 일괄 적용하므로 작은 글자나 얇은 선이 많은 PNG는 무손실 원본보다 경계가 부드러워지거나 압축 흔적이 보일 수 있다. 이는 원본을 보관하지 않고 저장 공간을 줄이는 MVP의 품질 절충이다.

---

## 8. 댓글과 좋아요

Post와 Project의 실제 FK를 유지하기 위해 댓글과 좋아요는 대상별 테이블로 나눈다. 하나의 `target_type`과 `target_id`로 두 종류의 대상을 표현하는 다형성 FK는 사용하지 않는다.

### 8.1 `post_comment`, `project_comment`

각 테이블은 동일한 컬럼 구조를 가진다. `post_comment.post_id`는 `post.id`, `project_comment.project_id`는 `project.id`를 참조한다.

| 컬럼 | PostgreSQL 타입 | NULL | 규칙 |
|---|---|---:|---|
| `id` | BIGINT | N | PK, TSID |
| `post_id` 또는 `project_id` | BIGINT | N | 해당 대상 테이블의 FK |
| `author_member_id` | BIGINT | N | FK → `member.id` |
| `body` | TEXT | N | 댓글 내용; `CHECK (char_length(body) <= 2000)` |
| `visibility` | VARCHAR(20) | N | CHECK: `PUBLIC`, `SECRET` |
| `created_at` | TIMESTAMPTZ | N | 생성 시각 |
| `updated_at` | TIMESTAMPTZ | N | 수정 시각 |
| `deleted_at` | TIMESTAMPTZ | Y | 삭제 시각. 논리 삭제 시 사용 |

- MVP에서는 대댓글을 지원하지 않는다.
- `PUBLIC` 댓글은 대상 콘텐츠가 PUBLIC일 때 Guest에게 노출할 수 있다.
- `SECRET` 댓글은 작성 회원, 대상 콘텐츠의 소유자, 권한이 있는 관리자만 볼 수 있다.
- 콘텐츠 상태와 댓글 공개 범위는 모든 목록·상세 API에서 서버가 검증한다.
- 댓글 작성자는 일반 회원으로 한정한다. 관리자는 댓글을 작성하지 않고 운영 권한으로 관리한다.

### 8.2 `post_like`, `project_like`

각 테이블은 대상별 FK를 가지며 동일한 구조를 사용한다.

| 컬럼 | PostgreSQL 타입 | NULL | 규칙 |
|---|---|---:|---|
| `id` | BIGINT | N | PK, TSID |
| `post_id` 또는 `project_id` | BIGINT | N | 대상 FK |
| `member_id` | BIGINT | N | FK → `member.id` |
| `created_at` | TIMESTAMPTZ | N | 좋아요 시각 |
| `deleted_at` | TIMESTAMPTZ | Y | 좋아요 취소 시각. 논리 삭제 |

- 부분 UNIQUE 인덱스 `UNIQUE (대상 ID, member_id) WHERE deleted_at IS NULL`로 활성 좋아요의 중복을 방지한다.
- 좋아요 취소는 `deleted_at`을 설정한다. 같은 회원이 다시 좋아요하면 새 행을 추가한다.
- 좋아요 수는 MVP에서 연관 행을 집계한다. 별도 카운터 컬럼은 두지 않는다.
- 좋아요 작성자는 일반 회원으로 한정한다. 관리자는 좋아요를 등록하거나 취소하지 않는다.

---

## 9. 제약 및 인덱스 기준

### 9.1 핵심 제약

- 모든 FK는 참조 대상의 존재를 보장한다.
- Post는 Category 0개 또는 1개, 기술 Tag 여러 개, 개인 Board 0개 또는 1개를 가진다.
- 한 Project에는 여러 Post를 연결할 수 있고, 한 Post는 Project를 0개 또는 1개 참조한다.
- Board 부모와 Post 작성자는 Board 소유자와 일치해야 한다.
- Category·Board의 최대 계층 깊이와 순환 방지는 애플리케이션에서 검증한다.
- Post·Project의 공개 상태, is_blocked 및 회원 상태를 확인하는 권한 검증은 DB FK만으로 대체하지 않는다.
- 콘텐츠의 일반 공개 조건은 visibility_status = PUBLIC AND is_blocked = FALSE다. 차단은 PUBLIC/HIDDEN 상태와 독립적으로 저장되며, 차단 해제는 공개 상태를 변경하지 않는다.

### 9.2 인덱스

다음 조회 경로를 기준으로 인덱스를 검토한다. 실제 생성 전 `EXPLAIN`과 서비스 쿼리 기준으로 조정한다.

- `member_oauth_identity(provider, provider_subject)` UNIQUE
- `admin_account(login_id)` UNIQUE
- `category(parent_id, display_order)` 및 `category(slug)` UNIQUE
- `tag(slug)` UNIQUE 및 `tag(status, display_order)`
- `board(owner_member_id, parent_id, display_order)`
- `post(author_member_id, visibility_status, is_blocked, created_at)`
- `post(category_id, visibility_status, is_blocked, published_at)`
- `post(board_id, visibility_status, is_blocked, published_at)`
- `post(project_id, visibility_status, is_blocked, published_at)`
- `post_tag(tag_id, post_id)`
- `project(owner_member_id, visibility_status, is_blocked, created_at)`
- `project_tag(tag_id, project_id)`
- `project_link(project_id, display_order)`
- `project_feature(project_id, display_order)`
- `project_media(project_id, display_order)`
- `post_comment(post_id, created_at)` 및 `project_comment(project_id, created_at)`
- `post_like(post_id, member_id) WHERE deleted_at IS NULL` 및 `project_like(project_id, member_id) WHERE deleted_at IS NULL` 부분 UNIQUE

---

## 10. 삭제 및 비활성화 정책

- Post와 Project의 `DELETED`는 논리 상태로 저장한다. 일반 조회·검색·댓글 조회에서 제외한다.
- 일반 사용자에게 Post와 Project를 노출할 때는 visibility_status = PUBLIC AND is_blocked = FALSE를 모두 확인한다. is_blocked = TRUE인 콘텐츠는 visibility 상태가 PUBLIC이어도 상세·목록·검색·게시판·공개 댓글·좋아요 및 미디어 조회에서 제외한다.
- 차단된 콘텐츠의 공개 댓글과 좋아요는 일반 조회 및 집계에서 제외한다. 비밀 댓글은 댓글 작성자, 콘텐츠 작성자, 권한이 있는 관리자만 조회할 수 있다는 댓글 접근 규칙을 계속 적용한다.
- 차단은 MANAGER 또는 MASTER가 설정하고 해제한다. 작성자는 차단 값을 바꿀 수 없다. 차단 해제는 visibility_status를 변경하지 않으므로 HIDDEN 콘텐츠는 계속 비공개로 남는다.
- Board는 하위 Board가 없을 때 삭제할 수 있다. 해당 Board의 Post를 미분류로 바꾸고 Board에 `deleted_at`을 설정한다.
- Category와 Tag는 참조 콘텐츠가 남아 있는 동안 물리 삭제하지 않는다. 비활성화 후 기존 콘텐츠 참조는 유지한다.
- 비활성 Category/Tag는 새 연결에 사용할 수 없지만, 기존 연결된 공개 Post·Project에서는 표시되고 해당 분류/태그로 계속 탐색할 수 있다.
- 댓글의 삭제는 `deleted_at`으로 표시한다. 삭제된 댓글 본문을 사용자에게 반환하지 않는다.
- 좋아요 취소는 `deleted_at`을 설정하며 일반 조회와 집계에서 제외한다.
- 회원 탈퇴 요청 즉시 Refresh Token 세션을 폐기하고 `member.status = WITHDRAWAL_PENDING` 및 탈퇴 요청·예정 시각을 기록한다. 계정의 쓰기를 막고 해당 회원의 Post·Project·Board·댓글·좋아요·미디어를 일반 조회·검색·집계에서 숨기되, 원래 상태는 보존해 7일 안에 취소할 수 있게 한다.
- 탈퇴 예정 시각까지 취소되지 않으면 회원, OAuth 식별자, Post·Project와 하위 데이터, Board, 댓글, 좋아요를 운영 데이터베이스에서 물리 삭제하고 회원 소유의 R2 이미지도 삭제한다. 외부 저장소 삭제는 재시도 가능한 작업으로 처리하며 삭제 완료를 확인한다. 법령상 보관 의무가 있는 관리자 접속기록만 식별정보·콘텐츠와 분리해 SECURITY.md 기준으로 보유한다.
- 개인정보가 든 백업은 별도로 접근을 제한하고 정해진 백업 만료 시점에 파기한다. 백업 복원 시 탈퇴 완료 기록을 다시 적용한 뒤 서비스에 노출한다. 백업 보유기간은 배포 환경의 백업 정책에 명시한다.
- Post·Project의 일반 논리 삭제 정책과 회원 탈퇴에 따른 최종 파기는 구분한다. 개별 콘텐츠 삭제는 `DELETED` 상태를 사용할 수 있지만, 탈퇴 데이터는 유예 기간 뒤 잔존 tombstone 없이 제거한다.

---

## 11. 후속 운영 결정

다음 항목은 배포 환경이 정해질 때 운영 구성으로 확정한다. 제품 정책과 API/DB 계약은 위에서 정한 값을 따른다.

1. **백업 보유기간**: 배포 환경의 백업 보유기간과 자동 만료를 정하고 탈퇴된 데이터를 복원 전에 제거하는 절차를 구성한다.
2. **미디어 전달 경로**: MVP는 비공개 R2와 15분 presigned URL을 사용한다. CDN을 도입할 때도 HIDDEN·차단·탈퇴 대기 콘텐츠의 권한 경계를 보존하도록 별도 설계한다.

---

## 12. 문서 간 책임

- 제품 기능과 사용자 흐름: `PRD.md`
- Feature 책임과 코드 구조: `ARCHITECTURE.md`
- 테이블, 관계, 제약, 인덱스: `DB.md`
- Endpoint, Request/Response: `API.md`
- 인증·인가와 토큰 보안: `SECURITY.md`
