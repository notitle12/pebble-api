# 로컬 개발 환경 가이드 (Local Development Guide)

본 문서는 Pebble 백엔드(`pebble-api`)의 로컬 개발 환경 구성 및 실행 방법을 설명합니다.

---

## 1. 개요 및 원칙

* **Docker Compose 기반 로컬 인프라**: PostgreSQL과 Redis는 로컬 머신(Homebrew 등)에 직접 설치된 인스턴스를 사용하지 않고, 반드시 **Docker Compose**를 통해 실행합니다.
* **Secret 및 환경변수 격리**: 민감한 정보(비밀번호, 시크릿 등)는 Git에 커밋하지 않으며, 실제 로컬 환경변수는 `.env` 파일로 관리합니다. `.env`는 Git에서 제외하고, `.env.example`만 Git에 커밋합니다.
* **Spring Boot 설정 분리**: 로컬 프로파일 설정은 `application-local.yml`에서 관리하며, 해당 실제 파일은 Git에서 제외하고 `application-local.yml.example`을 Git에 커밋합니다.
* **Docker Compose와 Spring Boot의 환경변수 역할 분리**: `.env` 파일은 Docker Compose에서 자동으로 사용됩니다. Spring Boot는 `.env` 파일 자체를 직접 읽지 않으며, 별도의 환경변수가 주입되지 않은 경우 `application-local.yml`에 정의된 fallback 기본값을 사용합니다.
* **데이터 영속성**: PostgreSQL 컨테이너는 Docker Volume(`postgres_data`)을 사용하여 컨테이너를 삭제하거나 재생성하더라도 데이터가 유지되도록 구성합니다.
* **Redis 데이터 특성**: Redis는 로컬 캐시 및 세션 등의 용도로 사용하며, 현재 구성에서는 별도의 Docker Volume을 사용하지 않습니다.

---

## 2. 파일 구조

```text
pebble-api/
├── docker-compose.yml                  # PostgreSQL 및 Redis 인프라 정의 (Git 관리)
├── .env.example                        # 환경변수 템플릿 (Git 관리)
├── .env                                # 실제 로컬 환경변수 (Git 제외, 로컬 전용)
├── docs/
│   ├── project-development-rules.md
│   └── local-environment-guide.md      # 로컬 개발 환경 가이드 (본 문서)
└── src/main/resources/
    ├── application.yml                 # 공통 애플리케이션 설정
    ├── application-local.yml           # 로컬 프로파일 설정 (Git 제외)
    └── application-local.yml.example   # 로컬 프로파일 설정 템플릿 (Git 관리)
```

---

## 3. 사전 요구사항

* **Docker & Docker Compose**: Docker Desktop이 설치되어 있고 실행 중이어야 합니다.
* **Java 21**: JDK 21이 설치되어 있어야 합니다.
* **Gradle Wrapper**: 프로젝트에 포함된 `gradlew`를 사용하므로 별도의 Gradle 설치는 필요하지 않습니다.

---

## 4. 로컬 환경 설정

### 4.1 환경변수 파일 준비 (`.env`)

프로젝트 루트에서 `.env.example`을 복사하여 실제 로컬 환경변수 파일을 생성합니다.

```bash
cp .env.example .env
```

`.env`는 Git에서 제외되므로 실제 로컬 환경에 필요한 값을 설정할 수 있습니다.

기본적인 `.env` 구성은 다음과 같습니다.

```dotenv
# PostgreSQL Container Configuration
POSTGRES_DB=pebble_db
POSTGRES_USER=pebble_user
POSTGRES_PASSWORD=pebble_password
POSTGRES_PORT=5432

# Redis Container Configuration
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=

# Spring Boot Application Local Configuration
DB_URL=jdbc:postgresql://localhost:5432/pebble_db
DB_USERNAME=pebble_user
DB_PASSWORD=pebble_password
```

> **주의:** `.env` 파일은 Docker Compose에서 자동으로 읽지만, Spring Boot가 `.env` 파일을 직접 읽는 것은 아닙니다. Spring Boot를 실행할 때 별도의 환경변수를 주입하지 않는 경우 `application-local.yml`에 정의된 fallback 기본값이 사용됩니다.

---

## 5. 로컬 인프라 실행

### 5.1 PostgreSQL 및 Redis 실행

Docker Compose를 통해 PostgreSQL과 Redis 컨테이너를 백그라운드로 실행합니다.

```bash
docker compose up -d
```

### 5.2 실행 상태 확인

```bash
docker compose ps
```

다음 서비스가 `Up (healthy)` 상태인지 확인합니다.

```text
pebble-postgres
pebble-redis
```

두 서비스 모두 `healthy` 상태가 되면 로컬 인프라가 정상적으로 준비된 것입니다.

---

## 6. 인프라 정상 동작 검증

### 6.1 PostgreSQL 준비 상태 확인

```bash
docker compose exec postgres pg_isready -U pebble_user -d pebble_db
```

정상적인 경우 다음과 유사한 결과가 출력됩니다.

```text
/var/run/postgresql:5432 - accepting connections
```

### 6.2 PostgreSQL SQL 연결 확인

PostgreSQL에 직접 접속하여 SQL 실행이 가능한지 확인합니다.

```bash
docker compose exec -e PGPASSWORD=pebble_password postgres \
  psql -U pebble_user -d pebble_db \
  -c "SELECT version();"
```

PostgreSQL 버전 정보가 출력되면 정상입니다.

### 6.3 Redis 연결 확인

```bash
docker compose exec redis redis-cli ping
```

정상적인 경우 다음과 같이 출력됩니다.

```text
PONG
```

---

## 7. Spring Boot 애플리케이션 실행

### 7.1 테스트 실행

Docker 인프라가 실행 중인 상태에서 전체 테스트를 실행합니다.

```bash
./gradlew test
```

테스트가 모두 성공하는지 확인합니다.

### 7.2 프로젝트 빌드

```bash
./gradlew build
```

다음과 같은 결과가 출력되면 빌드가 성공한 것입니다.

```text
BUILD SUCCESSFUL
```

### 7.3 Spring Boot 애플리케이션 실행

`local` 프로파일이 기본 활성화되어 있는 경우 다음 명령으로 애플리케이션을 실행할 수 있습니다.

```bash
./gradlew bootRun
```

정상적으로 실행되면 Spring Boot 애플리케이션이 기본적으로 `8080` 포트에서 기동됩니다.

---

## 8. 인프라 관리 명령어

### 8.1 컨테이너 중지

실행 중인 컨테이너를 중지하지만 컨테이너와 데이터는 유지합니다.

```bash
docker compose stop
```

다시 실행하려면:

```bash
docker compose start
```

### 8.2 컨테이너 종료 및 삭제

컨테이너를 삭제하지만 Docker Volume은 유지합니다.

```bash
docker compose down
```

PostgreSQL의 `postgres_data` Volume은 삭제되지 않으므로 기존 데이터가 유지됩니다.

다시 실행하면 기존 PostgreSQL 데이터를 그대로 사용할 수 있습니다.

```bash
docker compose up -d
```

### 8.3 컨테이너 및 PostgreSQL 데이터 전체 초기화

컨테이너와 연결된 Docker Volume까지 삭제합니다.

```bash
docker compose down -v
```

현재 구성에서는 PostgreSQL의 `postgres_data` Volume이 삭제되므로 **PostgreSQL 데이터베이스가 초기화됩니다.**

이 명령은 개발 중 DB를 완전히 초기화해야 할 때만 사용합니다.

> **주의:** `docker compose down -v`로 삭제된 PostgreSQL 데이터는 Volume에 저장되어 있던 기존 데이터까지 함께 삭제되므로 주의해야 합니다.

### 8.4 컨테이너 로그 실시간 확인

```bash
docker compose logs -f
```

특정 서비스의 로그만 확인하려면:

```bash
docker compose logs -f postgres
```

또는:

```bash
docker compose logs -f redis
```

---

## 9. 일반적인 개발 작업 흐름

일반적인 로컬 개발에서는 다음 순서로 작업합니다.

### 최초 환경 구성

```bash
cp .env.example .env
docker compose up -d
docker compose ps
./gradlew test
./gradlew bootRun
```

### 이후 개발 작업

Docker 컨테이너가 이미 존재하는 경우:

```bash
docker compose start
./gradlew test
./gradlew bootRun
```

### 개발 종료

애플리케이션을 종료한 후 Docker 인프라까지 중지하려면:

```bash
docker compose stop
```

다음 개발 시작 시:

```bash
docker compose start
```

### DB를 완전히 초기화해야 하는 경우

```bash
docker compose down -v
docker compose up -d
./gradlew test
```

> `down -v`는 PostgreSQL 데이터를 삭제하므로 일반적인 개발 종료 과정에서는 사용하지 않습니다.
