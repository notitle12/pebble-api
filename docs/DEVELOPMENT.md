# Pebble Backend Development Guide

> **검토용 초안:** 브랜치 이름과 커밋 메시지 규칙은 아래에서 제안한다. 함께 검토한 뒤 확정한다.

이 문서는 이슈를 코드 변경으로 옮기는 절차와 개발 중 지킬 기본 규칙을 정리한다. Git 명령 자체만 정하는 문서는 아니며, 브랜치·커밋·Pull Request 규칙도 포함한다.

## 1. 문서와 구현의 기준

요구사항이나 구현 사이에 차이가 있으면 다음 문서를 담당 영역의 기준으로 삼는다.

| 주제 | 기준 문서 |
|---|---|
| 제품 기능 및 MVP 범위 | `PRD.md` |
| 패키지 구조와 Feature 책임 | `ARCHITECTURE.md` |
| HTTP API 계약 | `API.md` |
| DB 테이블과 관계 | `DB.md` |
| 인증·인가와 보안 | `SECURITY.md` |
| 이슈부터 병합까지의 작업 절차 | `DEVELOPMENT.md` |

문서에서 정한 API나 DB 계약을 바꾸는 작업은 관련 문서도 함께 수정한다. 코드가 문서와 다르면 조용히 한쪽을 기준으로 삼지 말고, 이슈와 Pull Request에 차이 및 변경 이유를 적는다.

## 2. 로컬 개발 환경

### 필수 도구

- JDK 21
- 저장소에 포함된 Gradle Wrapper 사용
- 로컬 PostgreSQL을 실행할 Docker Desktop
- GitHub 저장소 접근 권한

전역 Gradle 설치 대신 저장소 루트에서 `./gradlew`를 사용한다. Windows에서는 `gradlew.bat`을 사용한다.

### 기본 명령

```bash
./gradlew bootRun
./gradlew test
./gradlew build
```

`bootRun`과 테스트는 해당 작업에 필요한 로컬 서비스와 설정이 준비되어 있어야 한다. 성공 여부가 확인되지 않은 환경 설정이나 명령 결과를 Pull Request에서 성공했다고 표시하지 않는다.

### 로컬 PostgreSQL

저장소 루트의 `compose.yaml`은 개발용 PostgreSQL만 실행한다. 애플리케이션은 IDE에서 실행하거나 Gradle로 실행하며, 운영 환경의 DB 서비스는 이 Compose 구성과 분리한다.

```bash
docker compose up -d postgres
docker compose ps
./gradlew bootRun
docker compose down
```

기본 접속 정보는 로컬 개발 전용이다. PostgreSQL 데이터는 `postgres_data` named volume에 보존한다. DB 이름, 사용자, 비밀번호, 호스트 포트는 `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_PORT` 환경변수로 바꿀 수 있다. 애플리케이션은 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`를 사용하며, 기본값은 위 Compose 설정과 일치한다.

로컬 DB 데이터를 모두 버리고 다시 만들 때만 `docker compose down -v`를 실행한다. 이 명령은 `postgres_data`의 데이터를 삭제한다. 운영 데이터에 이 개발용 Compose 명령을 사용하지 않는다.

Flyway가 이후 SQL 마이그레이션을 관리한다. 새 스키마 변경은 `src/main/resources/db/migration` 아래에 순서가 있는 버전 파일로 추가하며, Hibernate가 스키마를 자동 생성하지 않도록 `ddl-auto`를 `validate`로 둔다. 현재는 도메인 테이블을 만들지 않는다.

### 현재 저장소 상태와 설정 주의사항

- 현재 `build.gradle`은 Spring Boot 3.5.x와 Java 21을 사용한다. 실제 플러그인 버전은 `build.gradle`을 기준으로 한다.
- `gradlew`와 `gradlew.bat`이 포함되어 있다.
- Redis 접속 설정은 아직 정의되어 있지 않다.
- PostgreSQL의 로컬 개발 설정은 `compose.yaml`과 `src/main/resources/application.yaml`을 기준으로 한다. 운영 접속 정보와 실제 비밀번호, OAuth 비밀값, 서명 키는 저장소에 넣지 않는다.
- `.env`, `application-local.yml`, `application-secret.yml` 등 `.gitignore`에 지정된 비밀 설정 파일은 커밋하지 않는다.

## 3. Git 작업 절차

### 이슈

기능, 버그 수정, 기술 작업은 구현 전에 GitHub Issue에 목적과 완료 조건을 적는다. 작은 문서 수정처럼 별도 Issue가 과도한 경우에는 연관된 Issue에 묶을 수 있다.

Issue에는 가능한 한 다음 내용을 포함한다.

- 해결하려는 문제 또는 필요한 결과
- 범위와 완료 조건
- 변경할 API·데이터·보안 동작이 있다면 그 영향
- 확인할 테스트나 수동 검증 방법

### 브랜치 전략

- `dev`를 기본 개발·통합 브랜치로 사용한다. 기능, 버그 수정, 문서, 기술 작업은 `dev`에서 작업 브랜치를 분기해 진행하고 Pull Request로 `dev`에 병합한다.
- `main`은 안정화 및 배포 브랜치로 유지한다. 릴리스할 때 검증된 `dev` 변경을 `main`에 반영한다.
- GitHub 저장소의 기본 브랜치도 `dev`로 설정한다. 설정이 반영되기 전에는 작업 브랜치와 Pull Request의 기준 브랜치를 명시적으로 `dev`로 지정한다.
- 작업 브랜치 이름에는 작업 종류와 Issue 번호를 포함한다.

```text
feature/<issue번호>-<짧은-영문-요약>
fix/<issue번호>-<짧은-영문-요약>
docs/<issue번호>-<짧은-영문-요약>
chore/<issue번호>-<짧은-영문-요약>
```

예: `feature/12-member-withdrawal`, `docs/18-development-guide`. 최초 스켈레톤도 동일한 규칙으로 작업 브랜치에서 준비한 뒤 `dev`에 병합한다.

### 커밋 메시지 제안

Conventional Commits 형식을 기본 제안으로 사용한다.

```text
<type>(<scope>): <짧은 변경 설명>
```

예:

```text
feat(member): add withdrawal cancellation
fix(media): reject oversized uploads
docs: define local development workflow
test(auth): cover refresh token rotation
chore: update Gradle configuration
```

권장 type은 `feat`, `fix`, `docs`, `test`, `refactor`, `chore`다. 메시지는 변경 결과를 짧게 설명하고, 여러 목적을 한 커밋에 섞지 않는다. 저장소의 기존 커밋은 초기 스캐폴딩 커밋 하나뿐이므로 이 형식은 아직 확정된 관례가 아니라 제안이다.

## 4. 구현 규칙

- 작업 전 관련 Issue와 `ARCHITECTURE.md`를 읽고 변경 위치와 Feature 소유자를 확인한다.
- 기능 코드는 패키지별 Feature 구조에 둔다. 공통 계층은 실제로 여러 Feature에서 공유되는 책임에만 사용한다.
- Post와 Project는 서로 다른 Feature로 유지한다. 한 기능의 도메인 상태를 다른 Feature가 직접 변경하지 않는다.
- 요청된 범위에 필요한 변경만 한다. 기존 동작을 바꾸거나 문서 계약을 갱신해야 하면 Issue와 Pull Request에 적는다.
- 의존성을 추가하거나 버전을 바꾸는 경우 이유와 영향 범위를 Pull Request에 적는다.
- 비밀값, 운영 데이터, 실사용자 개인정보를 코드·테스트 데이터·로그에 넣지 않는다.

## 5. 테스트와 검증

- 동작 변경에는 해당 동작을 검증하는 테스트를 추가하거나 수정한다.
- 기본 테스트 명령은 `./gradlew test`다. 전체 빌드 확인에는 `./gradlew build`를 사용한다.
- DB나 Redis가 필요한 테스트는 실행에 필요한 서비스와 설정을 함께 명시한다. 현재 저장소에는 로컬 서비스 실행 구성이 없으므로, 그 구성을 추가하는 작업에서 실행 방법을 문서화한다.
- 테스트를 실행하지 못했거나 환경 때문에 일부만 실행했다면 Pull Request에 실행 명령과 제한 사항을 사실대로 적는다.
- 문서 전용 변경은 내용과 문서 간 용어·수치가 일치하는지 확인한다.

## 6. Pull Request와 병합 제안

Pull Request는 관련 Issue에 연결하고 다음 내용을 적는다.

- 무엇을 왜 변경했는지
- Issue의 완료 조건이 충족되었는지
- 실행한 테스트·검증 명령과 결과
- DB 마이그레이션, 설정, 보안, API 호환성에 미치는 영향
- 화면 변경이 있으면 확인 자료

검토자는 문서 계약, 접근 제어, 사용자 데이터 삭제, 미디어 권한 경계를 확인한다. 검토 의견을 해결하고 필수 검사가 통과한 뒤 저장소의 병합 규칙에 따라 병합한다. GitHub의 보호 브랜치와 필수 검사 설정은 저장소 설정에서 별도로 관리한다.

## 7. 자동화와 코드 품질 도구

### CI와 CD

- **CI (Continuous Integration)**는 브랜치 push나 Pull Request 때 빌드와 테스트를 자동 실행해 변경사항을 빠르게 확인하는 절차다. 전체 기능이 완성될 때까지 기다릴 필요는 없다. 로컬에서 기본 빌드·테스트가 재현 가능해지면 최소 CI를 추가하는 편이 좋다.
- **CD (Continuous Delivery/Deployment)**는 검증된 변경사항을 배포 가능한 산출물로 만들거나 실제 환경에 배포하는 절차다. 자동 배포는 배포 대상, 환경별 설정, 비밀값 보관, 되돌리기 방법이 준비된 뒤 추가한다. 따라서 초기에는 CI만 두고 CD는 배포 환경이 정해질 때 시작해도 된다.
- 현재 저장소에는 `.github/workflows` 자동화가 없다. 테스트는 Spring Boot 전체 컨텍스트를 띄우므로 PostgreSQL 등 로컬·CI 테스트 환경과 실행 방식이 먼저 정리되어야 한다. 그 후 Pull Request마다 적어도 빌드와 테스트를 실행하는 CI를 도입한다.

### 포매터와 정적 분석

- 포매터와 정적 분석은 현재 도입하지 않는다. 이들은 빌드·테스트에 필수인 도구가 아니며, 지금은 별도 설정 없이 개발을 시작한다.
- 코드 스타일 관리나 반복적으로 발생하는 오류 예방에 필요성이 생기면, 도입 목적과 적용 범위를 Issue에서 정한다. 도구를 추가할 때는 개발자 로컬 실행 방법과 CI 검사 여부도 함께 정한다.

### 저장소 템플릿

- 작업 요청 템플릿: `.github/ISSUE_TEMPLATE/task.md`
- 버그 제보 템플릿: `.github/ISSUE_TEMPLATE/bug-report.md`
- Pull Request 템플릿: `.github/PULL_REQUEST_TEMPLATE.md`
- 템플릿은 저장소의 기본 브랜치에 반영된 뒤 GitHub의 새 이슈·Pull Request 작성 화면에 적용된다.
