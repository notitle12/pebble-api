# Pebble Backend Architecture

Pebble Backend의 내부 코드 구조와 Feature 간 책임 및 의존 관계를 정의한다.

이 문서는 **코드를 어떻게 구성할 것인지**를 정의한다.

제품의 기능과 범위는 `PRD.md`, API 계약은 `API.md`, 데이터 저장 구조는 `DB.md`, 인증 및 인가는 `SECURITY.md`, 개발 작업 절차는 `DEVELOPMENT.md`에서 정의한다.

---

## 1. Architecture Overview

Pebble Backend는 다음 원칙을 기반으로 구성한다.

- Package by Feature
- DDD-lite
- Responsibility-Based Placement
- 필요한 계층만 사용하는 실용적인 Layered Architecture
- Feature 간 명확한 책임 분리
- Existing Code First
- Minimal Change
- Architecture Restraint

전체 구조는 다음과 같다.

```text
com.pebble.api
├── global
├── auth
├── member
├── admin
├── post
├── project
├── category
├── tag
├── board
├── comment
└── like
```

각 Feature는 자신의 비즈니스 책임을 소유한다.

`Post`와 `Project`는 서로 다른 목적과 비즈니스 규칙을 가지므로 별도의 Feature로 관리한다.

`Content`라는 별도의 Feature, 상위 패키지 또는 공통 도메인 모델을 기본 구조로 만들지 않는다.

---

## 2. Design Principles

### 2.1 Package by Feature

기능 단위로 코드를 그룹화한다.

```text
post/
project/
member/
category/
tag/
board/
comment/
like/
```

기술적 역할만을 기준으로 전체 애플리케이션을 다음과 같이 분리하지 않는다.

```text
controller/
service/
repository/
entity/
dto/
```

기능의 책임이 가까운 곳에 모이도록 구성한다.

---

### 2.2 DDD-lite

도메인의 핵심 개념과 비즈니스 규칙을 명확하게 표현하되, 이론적인 DDD 패턴을 모든 코드에 강제하지 않는다.

다음과 같은 요소를 필요한 경우에만 사용한다.

- Entity
- Value Object
- Repository
- Domain Rule
- Application Service

다음 요소를 처음부터 의무적으로 생성하지 않는다.

- Aggregate Factory
- Domain Service
- Domain Event
- Specification
- Port / Adapter
- 별도의 Persistence Entity
- 기타 추상화 계층

실제 복잡성이 발생했을 때 필요한 구조를 추가한다.

---

### 2.3 Responsibility-Based Placement

각 Feature 내부는 다음 Layer를 기준으로 구성한다.

```text
feature/
├── presentation/
├── application/
├── domain/
└── infrastructure/
```

presentation, application, domain, infrastructure는 Feature 내부의 책임을 구분하기 위한 기본 구조다.

각 Layer의 책임은 다음과 같다.

presentation: HTTP/API 요청과 응답

application: 유스케이스 실행 및 흐름 조정

domain: 핵심 비즈니스 규칙과 도메인 모델

infrastructure: DB, Redis, 외부 API 등 기술 구현

다만 모든 Feature가 모든 Layer를 실제로 사용할 필요는 없다.

특정 Layer에 속하는 코드가 없는 경우 해당 디렉터리를 생성하지 않는다.

예:

```text
post/
├── presentation/
├── application/
├── domain/
└── infrastructure/
```

Infrastructure 구현이 아직 필요하지 않다면:

```text
post/
├── presentation/
├── application/
└── domain/
```

이와 같이 실제로 필요한 Layer만 생성한다.

따라서 Layer의 구조적 기준은 유지하되, 빈 디렉터리는 만들지 않는다.

또한 Layer 내부에서 어떤 클래스를 어디에 배치할지는 클래스의 실제 책임을 기준으로 결정한다.

단순히 파일의 종류나 이름만을 기준으로 기계적으로 분류하지 않는다.

---

## 3. Feature Structure

현재 주요 Feature는 다음과 같다.

```text
com.pebble.api
├── global
├── auth
├── member
├── admin
├── post
├── project
├── category
├── tag
├── board
├── comment
└── like
```

각 Feature의 책임은 다음과 같다.

| Feature | 주요 책임 |
|---|---|
| `global` | 애플리케이션 전체에서 공유되는 공통 기술 요소 |
| `auth` | 로그인, OAuth 인증, 토큰 발급 및 갱신 등 인증 흐름 |
| `member` | 일반 회원의 계정 및 회원 상태 |
| `admin` | 관리자 계정 및 관리자 운영 기능 |
| `post` | 기술 블로그 게시글과 게시글의 비즈니스 상태 |
| `project` | 개발 프로젝트와 프로젝트의 비즈니스 상태 |
| `category` | 관리자가 운영하는 Post 공통 주제 분류 |
| `tag` | Post 기술 태그와 Project 기술 스택에 쓰이는 기술 어휘 |
| `board` | 회원이 자신의 Post를 정리하는 개인 블로그 게시판 |
| `comment` | 게시글 및 프로젝트에 대한 댓글 |
| `like` | 게시글 및 프로젝트에 대한 좋아요 |

---

## 4. Post와 Project

`Post`와 `Project`는 각각 독립적인 Feature다.

```text
post/
└── 기술 블로그 게시글

project/
└── 개발 프로젝트 / 포트폴리오
```

`Project`를 `Post`의 카테고리 또는 하위 타입으로 구현하지 않는다.

두 Feature는 서로 다른 목적과 데이터 및 비즈니스 규칙을 가진다.

### Post

`Post`는 기술 지식과 개발 경험을 공유하기 위한 게시글을 담당한다.

주요 책임 예시는 다음과 같다.

- 게시글 작성
- 게시글 조회
- 게시글 수정
- 게시글 삭제
- 게시글 공개 상태 관리
- 본문 및 코드 블록 관리
- 카테고리 연결
- 게시글 검색에 필요한 비즈니스 규칙

### Project

`Project`는 개발 프로젝트와 포트폴리오 정보를 담당한다.

주요 책임 예시는 다음과 같다.

- 프로젝트 작성
- 프로젝트 조회
- 프로젝트 수정
- 프로젝트 삭제
- 프로젝트 공개 상태 관리
- 프로젝트 아키텍처 및 주요 기능 정보
- 구조화된 주요 기능 목록(`ProjectFeature`)의 생성·수정·삭제·정렬
- 프로젝트 조회 및 검색에 필요한 비즈니스 규칙
- 기술 스택
- GitHub 정보
- 배포 정보
- 실행 및 다운로드 정보


### Post와 Project의 관계

Project와 관련된 기술 기록은 Post로 작성할 수 있다.

하나의 Project에 여러 Post를 연결할 수 있다. Post는 Project와 연결하지 않거나 하나에만 연결한다. MVP에서는 작성자가 자신의 Project에 자신이 작성한 Post를 연결한다.

Project의 진행 상태는 `IN_PROGRESS` 또는 `COMPLETED`이며, 공개 상태와 별도로 관리한다.

예:

```text
Project: Pebble
│
├── Post: Spring Security 인증 구조 구현
├── Post: PostgreSQL 설계 과정
├── Post: Redis Refresh Token 관리
└── Post: GitHub Actions 배포 구성
```

구체적인 관계 및 저장 방식은 `DB.md`에서 정의한다.

---

## 5. Feature Boundaries

각 Feature는 자신의 도메인 책임을 소유한다.

### 5.1 Auth

`auth`는 인증 과정을 담당한다.

포함되는 책임:

- Naver OAuth 로그인
- 관리자 로그인 흐름
- 인증 성공 처리
- Access Token 발급
- Refresh Token 발급 및 갱신
- 로그아웃 및 토큰 무효화 처리

`auth`가 `Member`의 일반적인 비즈니스 데이터를 소유하지 않는다.

예:

```text
auth
└── 로그인 및 인증

member
└── 회원 계정 및 회원 상태
```

---

### 5.2 Member

`member`는 일반 사용자 계정과 회원 상태를 담당한다.

예:

- 회원 식별
- 회원 정보
- 회원 상태
- 회원 탈퇴와 관련된 회원 도메인 규칙

탈퇴 요청, 7일 유예 상태, Naver 재인증 취소 및 예정 시각의 최종 파기 조정을 담당한다. 탈퇴 예약 시 각 소유 Feature에 공개 데이터 숨김을 요청하고, 유예 기간이 끝나면 Post·Project·댓글·좋아요·Board와 R2 미디어의 삭제를 각 소유 Feature에 조정한다. 탈퇴 취소 시 콘텐츠의 기존 상태를 복원한다. 탈퇴가 완료된 회원의 tombstone은 남기지 않는다.

로그인 자체의 흐름은 `auth`가 담당한다.

---

### 5.3 Admin

`admin`은 관리자 계정과 관리자 운영 기능을 담당한다.

관리자는 다른 Feature의 데이터를 소유하는 것이 아니다.

예를 들어 다음과 같은 구조를 만들지 않는다.

```text
admin/
└── domain/
    └── Post.java
```

Post의 소유자는 `post` Feature다.

관리자가 Post를 차단하거나 강제 삭제해야 한다면 `post` Feature가 제공하는 적절한 기능을 통해 처리한다. 관리자 차단은 Post 작성자가 관리하는 공개 상태와 별도로 유지한다.

즉,

> 운영 주체와 데이터 소유자는 다를 수 있다.

---

### 5.4 Post

`post`는 Post 도메인의 소유자다.

다음 책임을 다른 Feature에 위임하지 않는다.

- Post 상태
- Post 내용
- Post 작성자 관계
- Post 공개 여부
- Post 관리자 차단 여부
- Post 삭제 상태
- Post 자체의 비즈니스 규칙

댓글과 좋아요는 별도 Feature에서 담당한다.

---

### 5.5 Project

`project`는 Project 도메인의 소유자다.

다음 책임을 다른 Feature에 위임하지 않는다.

- Project 상태
- Project 내용
- Project 작성자 관계
- Project 공개 여부
- Project 관리자 차단 여부
- Project 삭제 상태
- Project 자체의 비즈니스 규칙

댓글과 좋아요는 별도 Feature에서 담당한다.

---

### 5.6 Category

`category`는 관리자가 운영하는 Post의 공통 주제 분류를 담당한다.

Category가 Post 자체를 소유하지 않는다.

예:

```text
category
└── Category

post
└── Post
```

카테고리는 상위 주제와 하위 주제로 최대 2단계까지 구성한다. Post는 하나의 최하위 카테고리에 지정될 수 있으며, 자식이 있는 상위 카테고리는 탐색 그룹으로 사용한다. Java, Spring, JavaScript와 같은 구체적인 기술을 공통 Category 트리에 넣지 않는다.

비활성 Category는 신규 선택에서 제외하되 기존 Post의 표시와 공개 탐색 필터에서는 유지한다.

Post와 Category의 구체적인 관계는 `DB.md`와 API 요구사항에 따라 정의한다.

---

### 5.7 Tag

`tag`는 여러 콘텐츠에서 재사용하는 표준 기술 어휘를 담당한다.

- 관리자가 기술 태그를 등록하거나 비활성화한다.
- Post는 여러 기술 태그를 사용할 수 있다.
- Project는 같은 기술 태그 목록을 기술 스택으로 사용한다.
- 일반 사용자는 기존 태그를 선택하며, 태그 자체를 임의 생성하지 않는다.
- 비활성 Tag는 신규 선택에서 제외하되 기존 Post·Project의 표시와 공개 탐색 필터에서는 유지한다.
- Tag Feature는 Post나 Project의 데이터 및 비즈니스 규칙을 소유하지 않는다.

---

### 5.8 Board

`board`는 회원이 자신의 블로그에서 Post를 정리하기 위한 개인 게시판 구조를 담당한다.

- Board는 소유 회원 한 명에게 귀속된다.
- 회원은 Board를 생성, 이름 변경, 순서 변경, 상위·하위 이동 및 삭제할 수 있다.
- Board 계층은 최대 3단계까지 허용한다.
- 부모 Board는 같은 회원이 소유해야 하며, 계층에 순환 참조를 만들 수 없다.
- Post는 작성자 소유의 Board에 0개 또는 1개 배치할 수 있다.
- Board는 Post의 내용이나 공개 상태를 소유하거나 변경하지 않는다.
- Board 삭제는 Post 삭제를 의미하지 않는다. 게시된 Post는 미분류 상태가 된다.
- Board 조회는 Post의 공개 상태를 우회하지 않는다.

Board 계층의 생성, 이동 및 조회는 `board` Feature가 담당한다. Post와 Board의 연결은 Post 작성자와 Board 소유자의 일치 여부를 검증한다.

---

### 5.9 Comment

`comment`는 댓글이라는 비즈니스 기능을 담당한다.

댓글의 대상은 Post 또는 Project가 될 수 있다.

```text
Post    ← Comment
Project ← Comment
```

Comment Feature가 Post나 Project 자체의 도메인 객체를 소유하지 않는다.

공개 댓글은 공개 콘텐츠에서 Guest에게 노출할 수 있다. 비밀 댓글은 작성자, 콘텐츠 소유자 및 권한이 있는 관리자만 볼 수 있으며, 댓글 조회 경로에서 이 권한을 확인한다. DB 저장 구조는 대상별 FK를 보유하도록 `DB.md`에서 정의한다.

댓글 작성과 본인 댓글 관리는 일반 회원(User)의 상호작용이다. MANAGER와 MASTER는 댓글을 작성하지 않고 운영·검수 목적으로 댓글을 관리한다.

---

### 5.10 Like

`like`는 좋아요 기능을 담당한다.

좋아요의 대상은 Post 또는 Project가 될 수 있다.

```text
Post    ← Like
Project ← Like
```

Like Feature가 Post나 Project 자체의 도메인 객체를 소유하지 않는다.

좋아요는 일반 회원(User)만 등록하거나 취소할 수 있다. MANAGER와 MASTER는 좋아요를 누르지 않는다.

---

## 6. Layer Responsibilities

Feature 내부에서 다음 영역을 필요한 경우 사용한다.

```text
presentation
      ↓
application
      ↓
domain

infrastructure
      ↓
domain
```

이 구조는 엄격한 Clean Architecture 구현을 의미하지 않는다.

각 영역의 기본 책임은 다음과 같다.

---

### 6.1 Presentation

외부 요청과 응답을 담당한다.

주요 책임:

- Controller
- Request DTO
- Response DTO
- HTTP 입력 검증
- HTTP 응답 변환

Presentation 계층에 비즈니스 규칙을 과도하게 작성하지 않는다.

Entity를 직접 API 응답으로 반환하지 않는다.

---

### 6.2 Application

하나의 사용 사례를 실행하기 위한 흐름을 조정한다.

주요 책임:

- Use Case 실행
- 여러 도메인 객체의 흐름 조정
- 트랜잭션 경계
- Feature 간 협력 조정
- Repository 호출 조정

Application 계층을 단순한 CRUD 메서드 모음으로 무조건 생성하지 않는다.

실제 orchestration이 필요한 경우에 사용한다.

---

### 6.3 Domain

Feature의 핵심 비즈니스 책임을 담당한다.

주요 책임:

- Entity
- Value Object
- Repository interface
- 핵심 비즈니스 규칙

도메인 객체가 단순 데이터 전달 객체가 되지 않도록 한다.

다만 모든 비즈니스 로직을 무조건 Entity에 넣어야 하는 것은 아니다.

---

### 6.4 Infrastructure

외부 기술과의 연결을 담당한다.

예:

- JPA 구현
- Redis
- OAuth client
- 외부 API
- 기술적인 저장소 구현

Infrastructure 구현이 Feature의 비즈니스 책임을 가져가지 않도록 한다.

---

## 7. Dependency Direction

기본적인 의존 방향은 다음을 따른다.

```text
Presentation
     ↓
Application
     ↓
Domain

Infrastructure
     ↓
Domain
```

의존 방향은 책임을 명확하게 하기 위한 기본 원칙이다.

실제 구현에서 Spring이나 JPA 등의 기술 의존성이 필요한 경우 프로젝트의 기존 구조를 우선한다.

Architecture 규칙을 만족하기 위해 불필요한 interface, adapter, wrapper를 추가하지 않는다.

---

## 8. Feature-to-Feature Collaboration

Feature 간 협력이 필요한 경우 해당 Feature의 책임을 침범하지 않는 방식으로 협력한다.

예를 들어 Post 작성 시 작성자 정보가 필요하다고 해서 `post`가 Member의 내부 구현을 직접 관리해서는 안 된다.

```text
post
  ↓
member
```

필요한 정보만 조회하거나 검증한다.

마찬가지로 댓글 작성 시 Post 또는 Project의 존재 및 상태를 확인해야 할 수 있다.

```text
comment
  ↓
post / project
```

이 경우 Comment가 Post 또는 Project의 내부 상태를 직접 변경하지 않는다.

Post 분류와 개인 게시판 연결도 동일한 소유권 원칙을 따른다.

```text
post
  ├── category  # 공통 주제 분류를 조회
  ├── tag       # 기존 기술 어휘를 선택
  └── board     # 작성자 소유 게시판인지 확인하고 배치
```

`category`, `tag`, `board`는 Post 본문이나 Post의 공개 상태를 소유하지 않는다. 게시판 Feature는 Post를 직접 수정하지 않으며, 게시판 배치 시 Post 작성자와 Board 소유자가 일치하는지 확인한다. Project는 기술 스택을 위해 `tag`의 기술 어휘를 사용한다.

### 원칙

> 다른 Feature의 데이터를 직접 소유하거나 내부 구현을 조작하지 않고, 해당 Feature가 제공하는 책임을 통해 협력한다.

다만 단순 조회나 명확한 도메인 관계까지 모두 별도 interface/adapter로 감싸지는 않는다.

추상화는 실제 결합도나 변경 가능성 때문에 필요한 경우에만 도입한다.

---

## 9. Entity Rules

Entity는 도메인의 상태와 해당 상태에 필요한 비즈니스 규칙을 표현한다.

### 기본 원칙

- JPA Entity는 Feature 내부에 둔다.
- Entity를 API Response로 직접 반환하지 않는다.
- `@Data`를 Entity에 사용하지 않는다.
- 필요한 경우 JPA와 도메인 모델을 하나의 Entity로 구성할 수 있다.
- Persistence Entity와 Domain Entity를 무조건 분리하지 않는다.
- 식별자는 프로젝트에서 정한 TSID 정책을 따른다.

예:

```text
post/
└── Post.java
```

단순 CRUD 수준의 Entity를 위해 별도의 Domain Entity와 Persistence Entity를 중복 생성하지 않는다.

---

## 10. Repository Rules

Repository는 도메인의 저장소 요구사항을 표현한다.

필요한 경우 다음과 같이 구성한다.

```text
domain/
└── PostRepository.java

infrastructure/
└── JpaPostRepository.java
```

Repository interface와 구현체를 무조건 분리할 필요는 없다.

Spring Data JPA의 기본 Repository 기능만으로 충분한 경우 기존 프로젝트 구조와 단순성을 우선한다.

Repository 추상화는 실제로 필요한 경우에만 추가한다.

---

## 11. DTO Rules

DTO는 외부 API와 내부 도메인의 경계를 명확하게 하기 위해 사용한다.

기본적으로 API DTO는 `presentation`에 둔다.

예:

```text
post/
└── presentation/
    ├── PostController.java
    └── dto/
        ├── CreatePostRequest.java
        └── PostResponse.java
```

다음과 같은 불필요한 DTO 중복을 만들지 않는다.

```text
presentation/dto/
application/dto/
domain/dto/
infrastructure/dto/
```

각 계층에서 서로 다른 표현이 실제로 필요할 때만 별도의 DTO를 만든다.

단순히 계층 구조를 맞추기 위한 Mapper도 만들지 않는다.

---

## 12. Cross-Cutting Concerns

여러 Feature에서 공통적으로 사용하는 기술 요소는 `global`에 둔다.

예:

```text
global/
├── config/
├── exception/
├── media/        # Object storage adapter and shared WebP processing
├── response/
├── security/
└── util/
```

공통 미디어 인프라는 Cloudflare R2 접근, 이미지 검증·리사이즈·WebP 재인코딩, 파생 파일 저장·삭제와 presigned URL 생성을 제공한다. MVP URL은 15분 만료로 발급한다. Post와 Project Feature가 업로드 권한, 콘텐츠 공개 상태, 미디어와 도메인 객체의 연결을 각각 소유하고 서명 URL 발급 전에 조회 권한을 확인한다. `global/media`는 이 비즈니스 규칙을 대신 판단하지 않는다.

단, `global`은 모든 것을 넣는 공용 폴더가 아니다.

다음 조건을 만족하는 경우에만 `global`에 배치한다.

- 특정 Feature에 속하지 않는다.
- 여러 Feature에서 공통으로 사용된다.
- Feature의 비즈니스 책임으로 볼 수 없다.

특정 Feature의 비즈니스 로직을 `global`로 이동시키지 않는다.

---

## 13. Security Architecture

인증과 인가는 `auth`, `member`, `admin`, `global/security`의 책임을 구분한다.

기본적인 책임은 다음과 같다.

```text
auth
├── OAuth 로그인
├── 관리자 로그인
├── Token 발급
└── Token 갱신

member
└── 일반 회원 도메인

admin
└── 관리자 계정 및 관리자 운영

global/security
└── Spring Security 기술 설정 및 공통 보안 인프라
```

구체적인 인증 방식, JWT 정책, Refresh Token 정책, Redis 사용 방식, Role 정책은 `SECURITY.md`에서 정의한다.

Architecture 문서에서 보안 정책의 세부 사항을 중복 정의하지 않는다.

---

## 14. Admin Moderation

관리자는 Post와 Project를 운영할 수 있지만 해당 도메인의 소유자가 아니다.

작성자의 공개 상태는 `visibility_status`가 소유하고, 관리자의 차단 여부는 Post·Project가 각각 소유한다. 일반 공개 여부는 공개 상태가 `PUBLIC`이고 관리자 차단이 해제된 경우에만 성립한다. 관리자는 각 Feature의 유스케이스를 통해 차단을 설정·해제하거나 콘텐츠를 강제 삭제한다.

예:

```text
관리자
   ↓
post Feature
   ↓
Post 차단 또는 강제 삭제
```

다음과 같은 구조는 지양한다.

```text
admin
└── PostRepository
```

또는

```text
admin
└── PostService
```

Post의 상태 변경 규칙은 `post` Feature가 소유해야 한다.

Project도 동일하다.

```text
관리자
   ↓
project Feature
   ↓
Project 차단 또는 강제 삭제
```

이를 통해 관리자 운영 기능과 실제 도메인 소유권을 분리한다.

관리자가 공통 Category나 기술 Tag를 관리하는 경우에도 각 분류 Feature가 상태와 저장을 소유한다.

```text
관리자
   ├── category Feature → 공통 주제 분류 관리
   └── tag Feature      → 기술 어휘 관리
```

`admin`은 `CategoryRepository`나 `TagRepository`를 직접 소유하지 않는다.

---

## 15. Feature 내부 구조 예시

### 단순 Feature

필요한 구조만 사용한다.

```text
category/
├── Category.java
└── CategoryRepository.java
```

### 일반적인 Feature

```text
post/
├── presentation/
│   ├── PostController.java
│   └── dto/
├── application/
│   └── PostService.java
├── domain/
│   ├── Post.java
│   └── PostRepository.java
└── infrastructure/
    └── JpaPostRepository.java
```

### 외부 연동이 필요한 Feature

```text
auth/
├── presentation/
├── application/
├── domain/
└── infrastructure/
    ├── oauth/
    └── token/
```

위 구조는 예시이며 모든 Feature가 동일한 구조를 가져야 한다는 의미가 아니다.

---

## 16. Architecture Restraint

Pebble Backend는 구조적인 복잡성을 필요한 수준으로 제한한다.

다음 구조를 사전에 의무화하지 않는다.

- Clean Architecture 전체 구현
- Hexagonal Architecture 전체 구현
- 모든 외부 시스템에 대한 Port / Adapter
- 모든 Service에 대한 Interface
- 모든 Entity에 대한 별도 Persistence Entity
- 모든 계층에 대한 Mapper
- Domain Service
- Domain Event
- Event Publisher
- Kafka
- 별도의 CQRS 구조
- 과도한 Facade
- 과도한 Generic Repository

새로운 추상화나 계층은 다음 질문에 답할 수 있을 때만 추가한다.

1. 현재 코드에서 실제 문제가 발생했는가?
2. 기존 구조로 해결하기 어려운가?
3. 변경 가능성 또는 결합도 감소에 실질적인 도움이 되는가?
4. 추가되는 복잡성보다 얻는 이점이 큰가?

그렇지 않다면 현재 구조를 유지한다.

---

## 17. Existing Code First

Architecture 문서는 기존 코드를 무시하고 새로운 구조를 강제로 적용하기 위한 규칙이 아니다.

작업을 시작할 때 다음을 우선한다.

1. 기존 Feature 구조 확인
2. 기존 책임과 의존 관계 확인
3. 현재 구현과 문서의 차이 확인
4. 필요한 범위만 수정

기존 코드가 현재 Architecture와 다르더라도 무조건 전체 구조를 재작성하지 않는다.

Issue의 범위를 벗어난 구조 개선을 임의로 수행하지 않는다.

---

## 18. Architecture Change Rules

새로운 Feature, 계층, 인터페이스 또는 공통 구조를 추가할 때 다음을 확인한다.

### 새로운 Feature

다음 조건을 만족할 때 독립 Feature를 고려한다.

- 독립적인 비즈니스 책임이 있는가?
- 자체적인 상태 또는 규칙을 가지는가?
- 다른 Feature와 책임을 명확하게 분리할 필요가 있는가?

단순히 클래스가 많아졌다는 이유만으로 Feature를 분리하지 않는다.

### 기존 Feature 확장

기존 Feature가 이미 해당 책임을 가지고 있다면 새로운 Feature를 만들지 않고 기존 Feature를 확장한다.

### 공통화

두 Feature에서 비슷한 코드가 발견되었다는 이유만으로 즉시 `global`로 이동하지 않는다.

실제로 공통 책임이라고 판단될 때만 공통화를 고려한다.

---

## 19. Architecture Decision Checklist

구조를 추가하거나 변경할 때 다음을 확인한다.

```text
[ ] 이 코드는 어느 Feature의 책임인가?
[ ] 기존 Feature로 처리할 수 없는가?
[ ] 새로운 Feature가 정말 필요한가?
[ ] 필요한 Layer만 생성했는가?
[ ] 다른 Feature의 책임을 침범하지 않는가?
[ ] 새로운 Interface가 실제로 필요한가?
[ ] 새로운 Mapper가 실제로 필요한가?
[ ] Entity를 불필요하게 분리하지 않았는가?
[ ] global에 넣을 이유가 명확한가?
[ ] 기존 코드보다 복잡해지지 않았는가?
[ ] Issue 범위를 벗어난 구조 변경은 없는가?
```

---

## 20. Final Architecture Principles

Pebble Backend의 Architecture는 다음 원칙을 최우선으로 한다.

1. **Feature가 비즈니스 책임의 기본 단위다.**
2. **Post와 Project는 서로 독립적인 Feature다.**
3. **`Content`라는 별도의 Feature나 공통 상위 패키지를 만들지 않는다.**
4. **`category`는 공통 주제 분류, `tag`는 관리되는 기술 어휘, `board`는 회원별 Post 정리 구조를 담당한다.**
5. **Feature 내부 Layer는 필요한 경우에만 만든다.**
6. **클래스는 폴더 이름이 아니라 책임을 기준으로 배치한다.**
7. **각 Feature가 자신의 도메인 상태와 규칙을 소유한다.**
8. **관리자는 다른 Feature의 데이터를 소유하지 않는다.**
9. **Feature 간 협력은 책임을 침범하지 않는 방식으로 수행한다.**
10. **DTO, Mapper, Interface, Adapter 등의 추상화는 필요한 경우에만 추가한다.**
11. **Clean Architecture나 Hexagonal Architecture를 형식적으로 구현하지 않는다.**
12. **기존 코드를 우선하고 최소한으로 변경한다.**
13. **Architecture는 현재 문제를 해결하기 위한 수단이며 목적 자체가 아니다.**

이 원칙을 통해 Pebble Backend는 프로젝트 규모에 맞는 명확한 구조를 유지하면서도, 실제 복잡성이 증가할 경우 필요한 방향으로 확장할 수 있도록 한다.
