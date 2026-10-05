# Pebble 프론트엔드 문서 안내

작성일: 2026-10-03. 사용자 web과 관리자 adminweb의 목표 설계 및 실제 구현 상태를 구분한다. 이 문서 묶음은 설계 초안이며 backend 제품·API·보안 계약을 바꾸지 않는다.

## 문서별 책임

| 문서 | 무엇을 정하는가 | 백엔드 문서와의 대응 |
|---|---|---|
| [PRD](PRD.md) | 사용자와 관리자에게 필요한 화면·행동·완료 조건 | 제품 요구사항 |
| [ARCHITECTURE](ARCHITECTURE.md) | 폴더·의존 방향·기능 책임·서버/브라우저 경계 | 내부 코드 설계 |
| [STATE](STATE.md) | 서버 데이터·폼·URL·인증 상태·캐시의 보관과 갱신 | 프론트 상태 설계, DB 복제 아님 |
| [API](API.md) | DTO·HTTP 응답·오류·미디어를 프론트에서 처리하는 방법 | 백엔드 API 계약의 소비 기준 |
| [SECURITY](SECURITY.md) | 로그인·갱신·권한·SSR·쿠키·콘텐츠 보안 | 프론트의 보안 계약 준수 |
| [DEVELOPMENT](DEVELOPMENT.md) | 작업·Git·실제 scripts·검증·인계 | 개발 절차 |
| [DEPLOYMENT](DEPLOYMENT.md) | Workers·Pages·Oracle API 연결과 배포 전 검증 | 프론트 운영 설계 |
| [DECISIONS](DECISIONS.md) | 제안·사용자 방향·계약 차이·미해결 판단 | 협의와 변경 기록 |
| [화면 명세](source/SCREEN_SPEC.md) | 사용자 화면·라우트·권한·API 목표 | 기존 설계 원본 유지 |
| [디자인](source/DESIGN.md) | 시각 방향·대표 시안·반응형·접근성 | 기존 초안, 최종 시안 아님 |
| [관리자 화면](../adminweb/SCREEN_SPEC.md) | 별도 관리자 사이트의 화면과 동작 | USER 화면과 구분 |

## 읽는 방법

처음 설계를 이해할 때는 PRD → ARCHITECTURE → STATE → DECISIONS를 읽는다. 실제 기능 작업에서는 공통 AGENTS와 관련 문서만 읽는다.

- 화면·UI: PRD + 화면 명세 + 디자인의 관련 절.
- 구조·상태: ARCHITECTURE + STATE.
- API 연동·폼·미디어: API + 해당 backend 계약 절.
- 로그인·권한: SECURITY + backend API·SECURITY의 관련 절.
- 관리자: 공통 프론트 기준 + adminweb README·화면 명세 + 관련 관리자 계약. 사용자 web 프레임워크를 강제하지 않는다.
- 검증·Git: [DEVELOPMENT](DEVELOPMENT.md) + [Pebble 공유 Git 작업 순서](../GIT_WORKFLOW.md).
- 배포: DEPLOYMENT + SYSTEM.

## 현재 확인된 구현

pebble-web은 Next.js 16.3.8·React 19.3.0·TypeScript 7.0.2·Node >=22 <23이다. 2026-10-03 Issue #2의 feature/2-public-post-list 브랜치에서 `/` 공개 게시글 목록 SSR, 로딩·빈 목록·오류·URL 페이지 이동을 구현했다. dev 통합 전 작업 상태다. 로그인·편집·댓글·배포 adapter는 아직 구현하지 않았다. scripts는 dev·build·start·typecheck·test·dev:mock이다.

Node 22에서 테스트 21개·타입 검사·빌드가 통과했다. 모의 API의 목록·페이지 이동·로딩·오류·재시도와 모바일 화면, 실제 로컬 API의 0개 빈 목록을 브라우저에서 확인했다. 실제 글이 있는 목록·운영 API·OAuth·Workers 배포는 미검증이다. 목 API 21개를 사용하는 dev:mock 미리보기를 추가했고 상단 제목·여백을 DESIGN 기준에 맞춰 조정했다. 로고는 SVG 초안이며 로그인 메뉴와 보조 패널은 미구현이다. 상세 인계와 상태별 실행 명령은 [공개 게시글 목록](source/PUBLIC_POST_LIST.md)을 따른다. 이전 환영 화면 조사와 문서 초안은 현재 구현 완료로 해석하지 않는다.

## 문서의 원본과 우선 기준

이 폴더는 Obsidian projects/pebble/frontend와 같은 원본이다. source는 기존 pebble-web/docs/frontend 폴더를 연결한다. 기존 설계 파일을 복제하거나 수정하지 않았다. pebble-api와 pebble-web의 공통 Issue·브랜치·PR 절차는 상위 [공유 Git 작업 순서](../GIT_WORKFLOW.md)가 기준이다.

제품 정책은 backend PRD, 외부 계약은 backend API, 인증 정책은 backend SECURITY가 기준이다. 현재 구현은 실제 코드·설정·검증 기록으로 판단한다. 이 묶음의 제안은 DECISIONS에 명시하며 충돌이 생기면 원본과 영향 범위를 확인한다. 과거 작업 과정은 source/GIT_WORKFLOW.md에 보존하며, 신규 작업은 상위 공유 Git 작업 순서를 따른다.

Issue #4의 feature/4-post-search에서 홈 검색·기술 태그와 URL 조건 보존을 구현했다. 목록 PR #3에 의존하며 dev 통합 전이다. 실제 로컬 태그 8개·검색 0개 계약과 목 브라우저 검색/복원을 검증했다. 상세 기록은 [홈 검색 인계](source/POST_SEARCH.md)를 따른다.

Issue #6 feature/6-public-post-detail에서 홈 제목 링크와 Guest SSR 상세(TEXT/CODE·복사·미존재/오류)를 구현했다. 선행 검색 PR #5에는 태그 드롭다운의 화면 넘침·선택 UI를 수정했다. 상세 목 브라우저·실제 미존재 조회 검증은 [상세 인계](source/POST_DETAIL.md)를 따른다. 실제 글 상세·Workers는 미검증이며 dev 통합 전이다.

Issue #8 feature/8-public-project-list에서 /projects의 공개 목록·검색·기술 태그·진행 상태와 헤더 메뉴를 구현했다. 글/상세/검색 PR 기반으로 dev 통합 전이다. 공통 Guest 조회/입력은 lib, 태그는 features/tag로 분리했다. 목 브라우저·실제 0개 API 검증은 [프로젝트 목록 인계](source/PROJECT_LIST.md)를 따른다. 실제 프로젝트가 있는 응답·상세·Workers는 미검증이다.

Issue #10 feature/10-public-project-detail에서 프로젝트 상세·외부 링크·관련 글 조회와 페이지 이동을 구현했다. PR #9 기반으로 dev 통합 전이다. 목 상세/관련 글/미존재·모바일과 실제 404를 검증했고 [프로젝트 상세 인계](source/PROJECT_DETAIL.md)에 기록했다. 실제 프로젝트가 있는 상세·미디어 갤러리·Workers는 미검증이다.

Issue #12 feature/12-classification-explore에서 /tags·/categories와 헤더 메뉴, 글 categoryId 필터를 구현했다. PR #11 기반으로 dev 통합 전이다. 실제 분류 5개/태그 8개·조합 검색 0개와 목 탐색/AND 검색·모바일을 검증했다. 공유 선택기는 components/choice-picker에 두며 상세 범위는 [분류 탐색 인계](source/CLASSIFICATION_EXPLORE.md)를 따른다. 운영 API·실제 글이 있는 분류 결과·Workers는 미검증이다.

## 실제 로컬 연동 검증 갱신 (2026-10-03, API Issue #58)

위 각 기능 인계의 “실제 글·프로젝트가 있는 응답 미검증”은 당시 데이터가 없었던 상태다. 이후 로컬 PostgreSQL에 전용 테스트 작성자·공개 글 24개·공개 프로젝트 2개를 준비했고, 실제 API 목록/검색/태그·분류 AND/상세/숨김 제외/관련 글 페이지 검증 6개와 브라우저 정상 콘텐츠 흐름을 확인했다. 미리보기 3100은 실제 API 8080에 연결했다. 재현·정리 방법은 [실제 API 프론트 검증](../../../../../LOCAL_FRONTEND_VERIFICATION.md)을 따른다. OAuth·회원 쓰기·R2·Workers는 각 기능 개발 시 별도로 검증한다.

Issue #14 / API #60의 테이블 명세서 첫 단계를 구현한다. TABLE은 version=1 JSON content로 저장하고 공개 상세에서 HTML 표로 표시한다. 개발 전용 /dev/table-spec 입력·미리보기와 실 API 저장/조회 검증을 제공하며 로그인·글 작성기 연결은 아직 남아 있다. 후속은 제한된 아키텍처, ERD 순이며 상세 계약은 [표현 블록 설계](../../../../POST_VISUAL_BLOCKS.md)와 source/TABLE_SPEC.md를 따른다.

테이블 첫 단계 검증 완료: API 전체 456개(격리 임시 DB/Redis), web 22개·타입·최종 운영 빌드 통과. 실제 PATCH 저장/Guest 상세와 모바일 표·개발 입력을 확인했고 운영에서는 /dev 경로가 HTTP 404다. 백엔드 PR #61과 web Issue #14를 함께 검토한다. 공개 작성/게시 흐름은 로그인·Post 편집기 연결 후 검증하며, 저장 API와 상세 렌더링은 이번에 실제 로컬로 확인했다.

ARCHITECTURE 후속: API #62 / Web #16에서 클라우드·Docker 그룹과 요소/연결을 제한형 JSON으로 저장하고 자동 배치한다. 개발 전용 /dev/architecture와 공개 상세를 제공하며 로그인·전체 작성기는 아직 후속이다. API 전체 462개와 실제 회원 PATCH/Guest 조회·잘못된 참조 400을 확인했다. 계약/인계는 POST_VISUAL_BLOCKS.md 및 source/ARCHITECTURE_BLOCK.md를 따른다. ERD는 다음 단계다.


2026-10-05 사용자 요청으로 블로그 생성·프로필·설정의 탈퇴 흐름을 개선하고 운영에 반영했다. 중복 검사, SNS 닉네임, 식별자 underscore, 사진 초안 및 원자 저장, 취소 시 진입 화면 복귀를 제공한다. API 전체 478개·프론트 113개 테스트 및 격리 Workers 빌드가 통과했다. 실제 OAuth/R2 쓰기와 대역 브라우저 검증은 구분하며 [블로그 생성·프로필 인계](source/BLOG_CREATION_PROFILE.md)를 작업 기준으로 확인한다.
