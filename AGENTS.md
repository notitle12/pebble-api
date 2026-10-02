# Pebble API - AI Coding Agent Guidelines

프로젝트 작업 원칙과 아키텍처 불변식이다. 사용자 요청과 실행 환경의 상위 지침을 우선한다. 세부 계약은 관련 docs 문서를 따른다.

## 1. Source of Truth

| 판단할 내용 | 기준 문서 |
|---|---|
| 제품 정책·범위 | docs/PRD.md |
| API 계약·HTTP 상태 | docs/API.md |
| DB 관계·제약·저장 정책 | docs/DB.md |
| 책임·계층·의존 방향 | docs/ARCHITECTURE.md |
| 인증·인가·OAuth·토큰 | docs/SECURITY.md |
| Issue·Branch·Test·Commit·PR | docs/DEVELOPMENT.md |

현재 작업에 필요한 문서와 절만 확인한다. 코드·문서 충돌을 숨기거나 임의로 정책을 바꾸지 않는다. 근거와 영향 범위를 확인하고 결정이 불명확하면 사실과 필요한 판단을 보고한다.

## 2. Existing Code First

관련 기존 코드의 네이밍·예외·DTO·트랜잭션 경계를 먼저 확인한다. 요구사항을 충족하는 기존 패턴을 따른다. 문서·보안과 충돌하는 패턴을 복제하거나 매 작업마다 전체 스타일을 통일하지 않는다.

## 3. Issue Scope

요청·Issue 목적·완료 조건을 먼저 확인한다. 하나의 동작 가능한 목표를 하나의 Issue·PR로 묶는다. 필요한 Issue는 생성하되 계층별로 과도하게 분할하지 않는다. 관련 없는 리팩터링·Feature 변경과 기존 문제는 분리한다.

## 4. Minimal Change

필요한 최소 변경을 우선한다. 클래스·Interface·Mapper·Adapter·dependency는 실제 필요가 있을 때만 추가한다. 기존 구조로 해결 가능한지 먼저 판단하며 미래 확장 가능성만으로 추상화하지 않는다.

## 5. Verification

동작 변경에는 관련 테스트를 추가·수정하고 실행한다. 가능한 경우 병합 전 전체 테스트를 확인한다. 동일 코드의 성공한 테스트를 이유 없이 반복하지 않는다. 실패를 무시하거나 테스트를 삭제·비활성화하지 않는다. 기대값 변경에는 근거가 있어야 한다. 명령·환경·결과·미확인 범위를 사실대로 기록하고 CI 성공과 실제 외부 서비스 검증을 구분한다.

## 6. Dependency Changes

기존 dependency로 해결할 수 없는 필요성과 기능 중복을 확인한다. 편의만으로 추가하지 않는다. 아키텍처·운영 영향을 관련 문서·설정과 대조하고 이유를 PR에 적는다.

## 7. Coding Conventions

- Java 21과 현재 Spring Boot 버전, 기존 네이밍·스타일을 따른다.
- 생성자 주입과 final 필드를 사용한다. 단순 주입은 @RequiredArgsConstructor, 검증·변환·객체 조립은 명시 생성자를 사용한다.
- Entity에 @Data를 쓰거나 API로 직접 반환하지 않는다. API DTO는 Feature의 presentation에 둔다. 계층마다 동일 DTO·Mapper를 복제하지 않는다.
- 주석은 한글, Issue·PR 설명은 자연스러운 서술형으로 작성한다. placeholder·생략 코드를 실제 구현에 남기지 않는다.

## 8. Configuration & Secrets

실제 비밀·토큰·OAuth credential·개인정보를 코드·Git·로그에 남기지 않는다. 기존 환경변수·설정 구조를 따르고 로컬 비밀 설정·생성한 키·초기 관리자 자격 증명을 커밋하지 않는다. 테스트는 대역 자격 증명과 임시 키를 사용한다.

## 9. Error Handling

성공·오류는 ApiResponse·ApiErrorResponse 계약을 따른다. MVC 예외는 ApiExceptionHandler와 ErrorCode·ApplicationException 규격을 사용한다. 공통 오류는 GlobalErrorCode, 업무 오류는 Feature가 소유한다. Feature별 ControllerAdvice나 별도 응답 체계를 추가하지 않는다. Filter 오류는 같은 공통 응답을 사용하는 EntryPoint·AccessDeniedHandler로 처리한다. 구조 변경 전 요구사항과 영향을 확인한다.

## 10. Package by Feature

최상위는 auth·member·admin·post·category·project 등의 비즈니스 기능으로 나눈다. 프로젝트 전체를 controller/service/repository로 분리하지 않는다. Feature 전용 코드는 해당 Feature에 둔다. global은 특정 Feature에 귀속되지 않는 공통 기술 책임만 담는다. 공통 HTTP 응답·예외·trace는 global.presentation에 둘 수 있다.

## 11. Responsibility-Based Placement

presentation/application/domain/infrastructure는 책임 경계이며 고정된 파일 템플릿이 아니다. 클래스 이름이나 폴더 모양으로 기계적으로 배치하지 않는다. 필요한 구성 요소만 만든다.

## 12. Feature Boundary

auth는 인증, member는 회원 계정·상태, admin은 관리자 계정·운영을 소유한다. post·project 등 각 Feature가 자신의 Domain을 소유한다. 관리자 운영 때문에 소유권을 옮기지 않는다. 다른 Feature 데이터·상태는 그 Feature의 Domain/Application 계약으로 다루며 내부 상태를 직접 변경하지 않는다.

## 13. Security Invariants

인증·인가 변경 전 SECURITY.md를 확인한다. 근거 없이 permitAll을 추가하거나 권한 검증을 제거하지 않는다. OAuth·JWT·Refresh Token·Role 정책을 임의로 변경하지 않는다. 테스트 통과만으로 보안 정책 준수를 판단하지 않는다.

## 14. Architecture Restraint

모든 기술 의존성을 Interface·Port·Adapter로 감싸지 않는다. 단순 CRUD에 Domain Service·Event·UseCase 계층을 강제로 추가하지 않는다. 실제 결합도·변경 필요·복잡성에 근거해 구조를 선택한다.

## 15. Existing Changes Safety

작업 시작 전 Git 상태를 확인하고 사용자 변경을 보존한다. 관련 없는 변경을 되돌리지 않는다. 전체 파일 교체 전 기존 변경과 범위를 확인한다. dev에서 작업 브랜치를 분기해 dev에 PR로 통합한다. main은 릴리스용이다.

브랜치 이름은 DEVELOPMENT.md의 `feature/<이슈번호>-<요약>`, `fix/<이슈번호>-<요약>`, `docs/<이슈번호>-<요약>`, `chore/<이슈번호>-<요약>` 규칙을 따른다. 개발 도구나 에이전트 이름을 접두사에 붙이지 않는다.

## 16. Model Selection & Delegation

주 모델·추론 수준은 사용자 선택을 따른다. 가장 가벼운 적합 모델을 사용한다. 지원되는 모델별 하위 Agent가 있으면 명확한 탐색·기존 패턴 구현·작은 수정·문서·테스트는 Luna에 우선 위임한다. 보안·OAuth·JWT·인가·동시성·무결성·트랜잭션·Feature 경계·문서 충돌의 중요한 판단은 주 Agent가 맡는다. 파일 수만으로 난이도를 판단하지 않는다.

## 17. Delegation Escalation

위임 중 새로운 설계·DB 관계·Feature 경계·보안·트랜잭션 판단, 문서 충돌, 기존 설계 우회, 범위 확대 또는 반복 실패가 생기면 임의로 진행하지 않고 근거·변경 파일·실패 원인을 주 Agent에 반환한다. 주 Agent는 이미 확인한 조사를 재사용한다.

## 18. Delegation Efficiency

작은 작업은 직접 처리한다. 기본 하위 Agent는 하나이며 독립 작업의 이득이 명확할 때만 병렬로 늘린다. 필요한 목표·파일·문서 절·완료 조건만 전달하고 전체 대화 복제를 피한다. 하위 Agent는 변경 요약·근거·테스트 결과·남은 문제를 반환한다. 주 Agent는 계약·범위·검증 결과를 확인하고 보안·트랜잭션 핵심 경계를 검토한다. 이유 없는 전체 재분석은 하지 않는다. 위임도 사용량을 소비하므로 낮은 모델 비용과 조정 비용을 함께 비교한다.

## 19. Context & Reasoning Efficiency

전체 저장소·모든 문서·긴 로그를 반복 출력하지 않는다. rg와 필요한 줄만 읽고 확인한 내용을 재사용한다. 같은 실패 시도를 반복하지 않는다. CI는 간격을 두고 필요한 필드·실패 구간만 확인한다. 승인 자동화·새 채팅이 절약을 보장하거나 한도를 초기화한다고 가정하지 않는다. 기능 완료 시 기준 브랜치·완료 상태·남은 문제를 짧게 인계한다.

## 20. Model Availability

지원되는 모델 ID·reasoning 값만 사용한다. 이 파일만으로 실행 중인 모델·추론 설정이 바뀐다고 가정하지 않는다. 위임 불가 시 현재 모델로 안전하게 가능한 작업을 진행한다. 안전한 판단이 어려우면 사실·작업 상태·필요한 판단을 보고하고 무리하게 결정을 확정하지 않는다. 모델 전환을 실행했다고 허위로 말하지 않는다.

## 21. Agent Decision Order

1. 사용자 요청과 Git 상태·기존 변경을 확인한다.
2. AGENTS.md와 현재 Issue의 목적·범위를 확인한다.
3. 복잡도를 판단해 직접 수행 또는 위임을 선택한다.
4. 관련 기존 코드와 필요한 문서 절을 확인한다.
5. 충돌을 해결하고 최소 변경으로 구현한다.
6. 필요한 테스트와 핵심 경계를 검증한다.
7. 실제 결과·미확인 사항·다음 작업을 보고한다.

## 22. Final Principle

기존 코드를 이해하고 현재 Issue 범위 안에서 필요한 만큼 변경한다. 중요한 판단은 주 Agent가 맡고 명확한 작업은 효율적으로 위임한다. 절약을 이유로 필요한 규칙·검증을 생략하지 않는다.
