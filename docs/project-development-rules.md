# Project Development Rules

## 1. Project Overview

### 1.1 Project

* Project Name: Pebble
* Backend Repository: pebble-api
* Frontend Repository: pebble-web
* Admin Repository: pebble-admin

### 1.2 Backend Tech Stack

* Java 21
* Spring Boot 3.5
* Gradle
* Spring Data JPA
* Hibernate
* PostgreSQL
* Spring Security
* JWT
* Redis
* Docker
* Docker Compose

---

## 2. Core Development Principles

### 2.1 Existing Code First

코드를 생성하거나 수정하기 전에 반드시 현재 프로젝트의 실제 파일 구조와 기존 코드를 먼저 확인한다.

* 기존 클래스와 설정을 확인한다.
* 기존 기능을 확인한다.
* 이미 존재하는 Service, Repository, DTO 등을 중복 생성하지 않는다.
* 기존 구조를 임의로 변경하지 않는다.
* 기존 코드를 추측하여 구현하지 않는다.

### 2.2 Scope Control

현재 작업의 Issue 범위를 우선적으로 구현한다.

* Issue와 관련 없는 기능을 임의로 추가하지 않는다.
* 향후 필요할 것이라는 이유만으로 기능을 미리 구현하지 않는다.
* 과도한 추상화나 디자인 패턴을 적용하지 않는다.
* 현재 프로젝트에서 실제로 필요한 수준으로 구현한다.

### 2.3 Code Completeness

코드를 생성하거나 수정할 때 기본적으로 파일 전체 내용을 기준으로 작업한다.

다음과 같은 생략 표현을 사용하지 않는다.

* `...`
* `// 이하 생략`
* `// 기존 코드 동일`
* `// TODO`
* `/* existing code */`

---

## 3. Architecture

### 3.1 Architecture Style

Package by Feature + DDD-lite + Layered Architecture를 사용한다.

과도한 Clean Architecture 또는 Hexagonal Architecture를 강제하지 않는다.

외부 시스템에 대한 추상화는 실제로 교체 가능성이 있거나 테스트 및 유지보수에 도움이 되는 경우에만 적용한다.

### 3.2 Package Structure

기본 패키지는 다음 구조를 따른다.

```text
com.pebble.api
├── global
│   ├── config
│   ├── exception
│   ├── response
│   ├── security
│   └── util
│
├── auth
│   ├── presentation
│   │   ├── controller
│   │   └── dto
│   │       ├── request
│   │       └── response
│   ├── application
│   │   ├── service
│   │   └── mapper
│   ├── domain
│   │   ├── entity
│   │   └── enum
│   └── infrastructure
│       ├── oauth
│       ├── redis
│       └── security
│
├── member
├── category
├── post
├── project
└── admin
```

각 도메인은 필요한 계층만 생성한다.

---

## 4. Layer Responsibilities

### 4.1 Presentation

Presentation Layer는 HTTP 요청과 응답을 담당한다.

Controller의 책임:

* HTTP 요청 수신
* Request DTO 검증
* Application Service 호출
* Response DTO 반환

Controller에서 다음 작업을 하지 않는다.

* Repository 직접 호출
* 비즈니스 로직 처리
* 트랜잭션 처리
* Entity를 직접 Response로 반환

### 4.2 Application

Application Layer는 Use Case를 담당한다.

주요 책임:

* 비즈니스 흐름 조합
* Domain 객체 사용
* Repository 호출
* 외부 시스템 호출
* Transaction 관리

Transaction은 기본적으로 Application Service에서 관리한다.

조회 기능은 가능한 경우 `readOnly = true`를 사용한다.

### 4.3 Domain

Domain Layer는 핵심 비즈니스 규칙과 상태를 담당한다.

Domain은 다음을 알지 않아야 한다.

* Controller
* HTTP
* Request/Response DTO
* Spring MVC
* 외부 API의 구체적인 구현

### 4.4 Infrastructure

Infrastructure Layer는 기술적인 구현을 담당한다.

예:

* JPA Repository 구현
* Redis
* OAuth Client
* 외부 API Client
* 외부 시스템 연동

---

## 5. DTO Rules

API Request/Response DTO는 Presentation Layer에 둔다.

```text
presentation
└── dto
    ├── request
    └── response
```

Entity를 API Response로 직접 반환하지 않는다.

필요한 경우 Application Layer에서 Entity를 Response DTO로 변환한다.

DTO에는 API 계약에 필요한 데이터와 검증 로직을 중심으로 구성한다.

---

## 6. Entity Rules

Entity는 Domain Layer에 둔다.

```text
domain
└── entity
```

Entity에는 핵심 상태와 도메인 규칙을 표현한다.

Entity를 Controller에서 직접 노출하지 않는다.

Entity와 DTO를 동일한 객체로 사용하지 않는다.

### 6.1 Entity ID

주요 Domain Entity의 식별자는 **TSID(Time-Sorted Unique Identifier)** 기반으로 사용한다.

* 일반적인 DB Auto Increment 방식의 ID를 기본 식별자로 사용하지 않는다.
* 새로운 Entity를 구현할 때 별도의 요구사항이 없다면 TSID 기반 식별자를 사용한다.
* TSID 생성 및 매핑 방식은 현재 사용 중인 Java, Spring Boot, Hibernate 버전과 호환되는 방식을 사용한다.
* TSID 생성 로직을 Entity마다 중복 구현하지 않는다.
* TSID 관련 공통 구현이 필요한 경우 프로젝트 전체에서 재사용할 수 있는 구조를 우선한다.
* API Response 또는 Request에서 ID를 표현할 때의 타입과 직렬화 방식은 프로젝트 전체에서 일관되게 유지한다.
* TSID 구현을 위해 불필요하게 복잡한 추상화나 별도의 아키텍처 계층을 추가하지 않는다.

---

## 7. Exception Handling

Global Exception Handling은 다음 구조를 기본으로 사용한다.

```text
global
└── exception
    ├── CustomException
    ├── ErrorCode
    ├── ErrorResponse
    └── GlobalExceptionHandler
```

Application 또는 Domain에서 발생한 예외를 적절한 ErrorCode로 관리한다.

Controller마다 개별적으로 예외를 처리하기보다 GlobalExceptionHandler를 사용한다.

---

## 8. API Response

공통 API Response가 필요한 경우 다음 구조를 기본으로 사용한다.

```text
global
└── response
    └── ApiResponse
```

API 응답 형식을 프로젝트 전체에서 일관되게 유지한다.

---

## 9. Authentication & Authorization

### 9.1 Member

일반 사용자와 관리자는 하나의 Member 도메인을 공유한다.

Role을 통해 권한을 구분한다.

```text
ROLE_USER
ROLE_ADMIN
```

### 9.2 OAuth

일반 사용자는 Naver OAuth 로그인을 사용한다.

Provider 예:

```text
NAVER
LOCAL
```

### 9.3 Admin

관리자는 ID + Password 기반 로그인을 사용한다.

비밀번호는 반드시 안전한 단방향 해시 방식으로 저장한다.

비밀번호를 평문으로 저장하거나 로그에 출력하지 않는다.

---

## 10. JWT

Access Token과 Refresh Token을 사용한다.

기본 원칙:

* Access Token은 짧은 만료 시간을 사용한다.
* Refresh Token은 상대적으로 긴 만료 시간을 사용한다.
* Refresh Token은 Redis에서 관리한다.
* Refresh Token Rotation을 적용한다.
* 이전 Refresh Token의 재사용을 감지할 수 있도록 설계한다.
* Logout 시 Refresh Token을 제거한다.
* 필요한 경우 Access Token Blacklist를 적용할 수 있다.

JWT Secret 및 기타 민감한 인증 정보는 코드에 하드코딩하지 않는다.

---

## 11. Security

다음 정보는 로그에 출력하지 않는다.

* Password
* JWT Secret
* Access Token 전체 값
* Refresh Token 전체 값
* 인증 Credential
* 불필요한 개인정보

Security 설정은 `global/security` 또는 인증 도메인에서 책임 범위에 맞게 관리한다.

---

## 12. Configuration & Secrets

환경별 설정은 Spring Profile과 환경변수를 사용하여 관리한다.

### 12.1 Local Environment

로컬 개발 환경의 PostgreSQL과 Redis는 기본적으로 Docker Compose를 통해 실행한다.

Homebrew 등 OS에 직접 설치된 PostgreSQL 또는 Redis를 프로젝트의 기본 개발 환경으로 사용하지 않는다.

로컬 개발 환경은 다음 구조를 기본으로 한다.

Project
├── docker-compose.yml
├── .env
├── .env.example
└── src/main/resources
    ├── application.yml
    └── application-local.yml

docker-compose.yml은 PostgreSQL 및 Redis Container 실행을 담당한다.

.env 파일은 로컬 환경에서 사용하는 실제 환경변수를 관리한다.

.env.example 파일은 필요한 환경변수 목록과 예시 값을 제공하며 Git에 커밋한다.

### 12.2 Environment Variables

민감한 설정값은 코드에 하드코딩하지 않는다.

다음 정보는 환경변수로 관리한다.

Database URL
Database Username
Database Password
Redis Host
Redis Port
Redis Password
JWT Secret
OAuth Client Secret
API Secret Key
기타 인증 정보

### 12.3 Git Rules

다음 파일은 Git에 커밋하지 않는다.

.env
.env.*
application-local.yml
application-secret.yml

단, 다음 파일은 Git에 커밋한다.

.env.example
application-local.yml.example
docker-compose.yml

실제 Password, Secret, Token 등의 민감한 값은 Git에 커밋하지 않는다.

### 12.4 Deployment Environment

로컬 개발 환경과 배포 환경의 설정을 분리한다.

로컬 개발 환경에서는 Docker Compose를 사용한다.

운영 환경에서는 배포 서버의 환경변수 또는 별도의 Secret 관리 방식을 사용한다.

Oracle Cloud 배포 환경의 실제 Secret 값은 Git Repository에 저장하지 않는다.


---

## 13. Docker & Local Infrastructure

그리고 기존 13~21번은 번호가 하나씩 밀리게 됨.

### 13.1 Local Infrastructure

로컬 개발에 필요한 PostgreSQL 및 Redis는 Docker Compose를 통해 실행한다.

기본적으로 다음 서비스를 구성한다.

* PostgreSQL
* Redis

필요하지 않은 서비스는 Docker Compose에 미리 추가하지 않는다.

### 13.2 Docker Compose


Docker Compose 파일은 프로젝트 루트에 둔다.

```text
docker-compose.yml
Container의 환경설정은 .env 파일을 통해 관리한다. 
민감한 값은 docker-compose.yml에 직접 하드코딩하지 않는다.
```

### 13.3 Data Persistence

```text
PostgreSQL 데이터는 Docker Volume을 사용하여 Container 재시작 시에도 유지될 수 있도록 구성한다.
Redis는 현재 프로젝트 요구사항에 맞는 수준으로 구성한다.
불필요하게 복잡한 Docker 네트워크 또는 인프라 구성을 추가하지 않는다.
```

### 13.4 Application Connection

```text
Spring Boot 애플리케이션은 로컬 개발 환경에서 Docker Compose로 실행된 PostgreSQL 및 Redis와 연결할 수 있어야 한다.
Docker 환경에서 애플리케이션을 실행하지 않는 경우에도 호스트에서 실행되는 Spring Boot 애플리케이션이 Docker Container의 PostgreSQL 및 Redis에 연결할 수 있어야 한다.
```

## 14 Naming Convention

### 14.1 Class

PascalCase

```text
MemberService
PostController
JwtProvider
```

### 14.2 Method / Variable

camelCase

```text
findMember()
accessToken
refreshToken
```

### 14.3 Constant

UPPER_SNAKE_CASE

```text
ACCESS_TOKEN_TIME
REFRESH_TOKEN_TIME
```

---

## 15. Dependency Rules

새로운 Dependency를 추가하기 전에 현재 프로젝트의 `build.gradle`을 확인한다.

이미 존재하는 Dependency를 중복 추가하지 않는다.

현재 프로젝트에서 실제로 사용하지 않는 Dependency를 추가하지 않는다.

Dependency 추가가 필요한 경우 해당 Issue의 목적과 직접적으로 관련되어 있어야 한다.

---

## 16. Git Workflow

### 16.1 Branch Structure

기본 브랜치 구조:

```text
main
└── dev
    ├── feature/project-init
    ├── feature/auth
    ├── feature/member
    ├── feature/category
    └── feature/post
```

### 16.2 Branch Responsibilities

`main`

* 배포 가능한 안정 버전
* 직접 개발하지 않는다.

`dev`

* 개발 통합 브랜치
* Feature Branch의 결과를 통합한다.

`feature/*`

* 실제 기능 개발 브랜치
* `dev`에서 생성한다.

### 16.3 Feature Branch

예:

```bash
git checkout dev
git pull origin dev
git checkout -b feature/auth
```

작업 완료 후:

```text
feature/auth
    ↓
Pull Request
    ↓
dev
```

---

## 17. GitHub Issue

GitHub Issue는 의미 있는 하나의 작업 단위로 작성한다.

Entity, Repository, Service처럼 지나치게 작은 단위로 Issue를 분리하지 않는다.

예:

```text
[Member] 회원 도메인 구현
```

Issue 내부 Checklist를 통해 세부 작업을 관리한다.

Issue와 Branch의 이름은 작업 내용을 명확하게 표현한다.

GitHub Issue 번호는 GitHub에서 자동으로 부여하므로 제목에 직접 번호를 작성하지 않는다.

---

## 18. GitHub Milestone

Milestone은 개발 단계 또는 큰 목표를 나타낸다.

예:

```text
M1. 프로젝트 기반 구축
M2. 회원 및 인증
M3. 카테고리
M4. 게시글
M5. 프로젝트
M6. 관리자
M7. 테스트 및 안정화
```

Milestone을 세부 구현 작업 단위로 사용하지 않는다.

---

## 19. Development Workflow

모든 작업은 다음 순서를 기본으로 한다.

```text
GitHub Issue 확인
        ↓
현재 Branch 확인
        ↓
현재 프로젝트 구조 확인
        ↓
docs/project-development-rules.md 확인
        ↓
관련 Docker / 환경설정 확인
        ↓
기존 코드 확인
        ↓
현재 상태 분석
        ↓
필요한 변경사항 정리
        ↓
구현
        ↓
빌드
        ↓
테스트
        ↓
변경사항 확인
        ↓
Commit
        ↓
Push
        ↓
Pull Request
```

---

## 20. AI Coding Agent Rules

AI Coding Agent가 작업할 경우 다음 규칙을 반드시 따른다.

### 작업 시작 전

코드를 수정하기 전에 반드시 다음을 확인한다.

1. 현재 Branch
2. 현재 프로젝트 파일 구조
3. `docs/project-development-rules.md`
4. `build.gradle`
5. application 설정
6. 관련 Domain의 기존 코드
7. 관련 테스트 코드

### 작업 시작 전 보고

코드를 수정하기 전에 다음 내용을 간단히 보고한다.

```text
현재 상태:
필요한 작업:
예상 변경 파일:
```

### 구현

확인되지 않은 기존 코드를 추측하여 구현하지 않는다.

기존 코드와 충돌하는 경우 기존 구조를 먼저 분석한다.

중복 클래스, 중복 Repository, 중복 DTO, 중복 설정을 생성하지 않는다.

Issue 범위를 벗어난 리팩토링을 하지 않는다.

### 검증

구현 후 반드시 가능한 범위에서 다음을 실행한다.

```bash
./gradlew build
./gradlew test
```

빌드 또는 테스트 실패 시 원인을 분석하고 가능한 범위에서 수정한다.

### 결과 보고

작업 완료 후 다음을 보고한다.

```text
변경된 파일:
주요 변경사항:
빌드 결과:
테스트 결과:
Issue 완료 여부:
추가로 필요한 작업:
```

---

## 21. Testing

테스트는 다음 우선순위를 기본으로 한다.

1. Application Service
2. Domain Business Rule
3. Repository Integration Test
4. Security / Authentication
5. Controller

기능 구현 시 관련 테스트를 가능한 범위에서 함께 작성한다.

기존 테스트가 있는 경우 기존 테스트가 깨지지 않는지 확인한다.

---

## 22. General Rules

* 단순한 문제에 과도한 설계를 적용하지 않는다.
* 미래의 요구사항을 추측하여 구조를 복잡하게 만들지 않는다.
* 프로젝트 전체의 일관성을 우선한다.
* 기존 코드와 새로운 코드의 스타일을 통일한다.
* 모든 변경은 가능한 한 작은 단위로 유지한다.
* 작업 전에 확인하고, 구현 후 검증한다.
