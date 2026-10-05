# 공통 에이전트 문서 진입점

이 파일은 모든 프로젝트의 읽기 순서를 관리하는 단일 원본이다. 세부 작업 규칙은 책임과 프로젝트에 따라 분리한다. 사용자 요청과 상위 실행 지침을 우선하고, 문서·코드 충돌을 임의로 숨기거나 정책을 바꾸지 않는다.

## 작업 시작 시 읽는 순서

1. 사용자 요청·작업 대상·Git 상태·기존 변경을 확인한다.
2. [공통 개발 원칙](common/DEVELOPMENT.md)을 읽는다.
3. 프론트 작업이면 [프론트 공통 기준](common/frontend/GUIDELINES.md), 백엔드 작업이면 [백엔드 공통 기준](common/backend/GUIDELINES.md)을 읽는다. 양쪽을 변경하는 작업은 둘 다 읽는다.
4. 해당 프로젝트의 projects/<프로젝트>/README.md를 읽고 담당 영역의 지침을 확인한다. 디렉터리 이름만으로 프로젝트나 영역을 추정하지 말고 요청·실제 코드·저장소 설정을 함께 확인한다.
5. API·인증·데이터 저장처럼 경계를 넘는 작업은 필요한 계약 절을 함께 읽는다. 프론트 작업이라고 백엔드 API 계약을 생략하거나 모든 백엔드 구현 문서를 전부 읽지 않는다.
6. 같은 작업에서 확인한 문서는 재사용하고 변경되거나 새로 관련된 문서만 추가로 읽는다.

## Pebble의 영역별 진입점

| 작업 | 읽을 문서 |
|---|---|
| pebble-api | projects/pebble/README.md → backend/GUIDELINES.md → 관련 api/docs 계약 |
| pebble-web | projects/pebble/README.md → frontend/README.md → 관련 사용자 화면·API 계약 |
| adminweb | projects/pebble/README.md → adminweb/README.md → 관련 관리자 API·보안 계약 |
| 프론트·백엔드 연동 | 양쪽 공통 기준과 해당 영역의 지침, API·SECURITY 관련 절 |

Pebble의 두 저장소에서 새 작업을 시작할 때는 [공유 Git 작업 순서](projects/pebble/GIT_WORKFLOW.md)를 따른다. 이슈 생성부터 `dev` 기준 브랜치, push, PR, 검토·CI, 병합까지의 공통 기준은 이 문서가 단일 원본이다.

다른 프로젝트에는 Pebble의 Java 버전·토큰·R2·배포·Git 정책을 자동 적용하지 않는다. 새 프로젝트는 프로젝트 전용 README와 필요한 영역 문서를 연결한다. 문서가 없거나 접근할 수 없으면 그 사실을 보고하고 확인 가능한 범위만 진행한다.

## 원본과 연결

문서 원본은 현재 pebble-api/docs/system에 버전 관리한다. 모든 프로젝트용 원칙이 백엔드 코드의 소유라는 의미는 아니다. 독립 문서 저장소로 옮길 때는 진입점과 연결 경로를 함께 갱신한다.

Obsidian의 AGENTS.md·common·projects는 이 원본을 심볼릭 링크로 연결한다. 기존 Vault의 backend·frontend 사본은 archive/2026-10-03-document-layout 아래에 보존하며 현재 작업 기준으로 사용하지 않는다.

각 Git 저장소의 AGENTS.md는 공통 진입점의 경로와 프로젝트 식별만 제공한다. 도구가 생성하는 프레임워크 지침은 그 저장소에 보존한다. 다른 컴퓨터·클라우드에서는 공통 문서를 함께 제공하고 연결 경로를 확인한다. 위 링크는 이 진입점 디렉터리를 기준으로 해석한다.
