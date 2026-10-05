# Pebble 백엔드 작업 기준

기존 pebble-api AGENTS.md의 프로젝트 전용 규칙을 보존해 분리한 문서다. 아래 docs 경로는 pebble-api 저장소 루트를 기준으로 한다. 공통 개발 원칙은 [공통 개발 문서](../../../common/DEVELOPMENT.md)에 있다. API·제품·보안·저장 원본은 contracts 폴더의 링크에서 읽을 수 있다.

## 1. Source of Truth

pebble-api와 pebble-web의 이슈·브랜치·push·PR·검토·병합 순서는 [공유 Git 작업 순서](../GIT_WORKFLOW.md)를 따른다. 아래 계약 문서의 기존 작업 기록은 실제 당시 상태를 설명하며, 새 작업에는 공유 순서를 적용한다.

| 판단할 내용 | 기준 문서 |
|---|---|
| 제품 정책·범위 | [PRD.md](contracts/PRD.md) |
| API 계약·HTTP 상태 | [API.md](contracts/API.md) |
| DB 관계·제약·저장 정책 | [DB.md](contracts/DB.md) |
| 책임·계층·의존 방향 | [ARCHITECTURE.md](contracts/ARCHITECTURE.md) |
| 인증·인가·OAuth·토큰 | [SECURITY.md](contracts/SECURITY.md) |
| 구현·테스트·로컬 개발 | [DEVELOPMENT.md](contracts/DEVELOPMENT.md) |

새 작업을 시작할 때 [전체 시스템 구조](../SYSTEM.md)를 먼저 읽고 api·web·adminweb 구성과 배포 방향·책임 경계를 확인한다. 시스템 구조나 인증·배포 경계를 변경하면 이 문서도 갱신한다. Obsidian의 projects/pebble 폴더는 이 문서 원본을 연결해 보며 별도 사본을 만들지 않는다.

현재 작업에 필요한 문서와 절만 확인한다. 코드·문서 충돌을 숨기거나 임의로 정책을 바꾸지 않는다. 근거와 영향 범위를 확인하고 결정이 불명확하면 사실과 필요한 판단을 보고한다.

## 2. Coding Conventions

- Java 21과 현재 Spring Boot 버전, 기존 네이밍·스타일을 따른다.
- 생성자 주입과 final 필드를 사용한다. 단순 주입은 @RequiredArgsConstructor, 검증·변환·객체 조립은 명시 생성자를 사용한다.
- Entity에 @Data를 쓰거나 API로 직접 반환하지 않는다. API DTO는 Feature의 presentation에 둔다. 계층마다 동일 DTO·Mapper를 복제하지 않는다.
- 주석은 한글, Issue·PR 설명은 자연스러운 서술형으로 작성한다. placeholder·생략 코드를 실제 구현에 남기지 않는다.

## 3. Error Handling

성공·오류는 ApiResponse·ApiErrorResponse 계약을 따른다. MVC 예외는 ApiExceptionHandler와 ErrorCode·ApplicationException 규격을 사용한다. 공통 오류는 GlobalErrorCode, 업무 오류는 Feature가 소유한다. Feature별 ControllerAdvice나 별도 응답 체계를 추가하지 않는다. Filter 오류는 같은 공통 응답을 사용하는 EntryPoint·AccessDeniedHandler로 처리한다. 구조 변경 전 요구사항과 영향을 확인한다.

## 4. Package by Feature

최상위는 auth·member·admin·post·category·project 등의 비즈니스 기능으로 나눈다. 프로젝트 전체를 controller/service/repository로 분리하지 않는다. Feature 전용 코드는 해당 Feature에 둔다. global은 특정 Feature에 귀속되지 않는 공통 기술 책임만 담는다. 공통 HTTP 응답·예외·trace는 global.presentation에 둘 수 있다.

## 5. Responsibility-Based Placement

presentation/application/domain/infrastructure는 책임 경계이며 고정된 파일 템플릿이 아니다. 클래스 이름이나 폴더 모양으로 기계적으로 배치하지 않는다. 필요한 구성 요소만 만든다.

## 6. Feature Boundary

auth는 인증, member는 회원 계정·상태, admin은 관리자 계정·운영을 소유한다. post·project 등 각 Feature가 자신의 Domain을 소유한다. 관리자 운영 때문에 소유권을 옮기지 않는다. 다른 Feature 데이터·상태는 그 Feature의 Domain/Application 계약으로 다루며 내부 상태를 직접 변경하지 않는다.

## 7. Security Invariants

인증·인가 변경 전 SECURITY.md를 확인한다. 근거 없이 permitAll을 추가하거나 권한 검증을 제거하지 않는다. OAuth·JWT·Refresh Token·Role 정책을 임의로 변경하지 않는다. 테스트 통과만으로 보안 정책 준수를 판단하지 않는다.

## 8. Existing Changes Safety

작업 시작 전 Git 상태를 확인하고 사용자 변경을 보존한다. 관련 없는 변경을 되돌리지 않는다. 전체 파일 교체 전 기존 변경과 범위를 확인한다. dev에서 작업 브랜치를 분기해 dev에 PR로 통합한다. main은 릴리스용이다.

브랜치 이름은 DEVELOPMENT.md의 `feature/<이슈번호>-<요약>`, `fix/<이슈번호>-<요약>`, `docs/<이슈번호>-<요약>`, `chore/<이슈번호>-<요약>` 규칙을 따른다. 개발 도구나 에이전트 이름을 접두사에 붙이지 않는다.
