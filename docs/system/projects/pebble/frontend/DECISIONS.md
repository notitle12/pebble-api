# Pebble 프론트엔드 결정과 미해결 사항

작성일: 2026-10-03. 상태는 계약 기준, 사용자 방향, 제안, 미해결로 구분한다. 제안을 이미 확정된 정책으로 표현하지 않는다. 미해결 사항은 관련 작업 전에 사실·영향을 확인하며 모든 작업을 일괄 중단시키는 승인 목록이 아니다.

## 1. 결정 목록

| ID | 주제 | 상태 | 기준·다음 처리 |
|---|---|---|---|
| F01 | api 공유, web/adminweb 분리 | 사용자 확인 | 별도 관리자 backend를 추가하지 않음 |
| F02 | 공개 web SSR, 관리자 SPA | 사용자 방향 | 모든 web 화면 SSR 강제는 아님 |
| F03 | Oracle API·Cloudflare DNS·R2 | 사용자 현황 | 실제 API 배포 완료·hostname은 미확정 |
| F04 | web Workers 배포 | 우선 검토 방향 | 설치된 Next.js와 배포 도구 호환성 검증 필요 |
| F05 | app/features/components/lib | 기존 초기 구성과 정합 | feature 내부 api·components·hooks 유지, shared/model/ui로 별도 이전하지 않음 |
| F06 | fetch·React 상태로 시작 | 제안 | 반복 조회·폼 복잡성 발생 시 필요한 도구만 검토 |
| F07 | 초기 장기 공유 캐시 없음 | 제안 | 공개 상태·개인 정보·signed URL 보호를 우선, 성능 측정 뒤 검토 |
| F08 | 메모리 JWT·HttpOnly refresh | 기존 보안 계약 | BFF·토큰 저장 방식 변경은 별도 계약 판단 |
| F09 | adminweb 프레임워크·저장소 | 미해결 | 로컬 경로·실제 스택·scripts 확인 후 구체화 |
| F10 | OAuth callback 최종 경로 | 미해결 | 기존 두 후보 중 실제 URI와 함께 결정 |
| F11 | 개인 블로그 메타데이터 | 미해결 | 빈 블로그와 handle→member ID 계약 필요 여부 확인 |
| F12 | Project 목록 media | 미해결 | 목록 DTO에 없는 media를 상세 반복 조회로 우회하지 않음; Post 썸네일은 별도 기존 계약 |
| F13 | 다중 탭 refresh 조정 | 구현 전 판단 | 지원 브라우저와 실제 경합·응답 유실 검증 |
| F14 | 편집기·작성 중 복구 | 제안/범위 판단 | TEXT/CODE로 시작 검토, 자동 저장·영구 초안은 별도 요구 |

## 2. 기존 문서와 현재 코드의 차이

현재 src에는 환영 화면 /, layout, globals.css만 확인된다. API·로그인·개인 블로그·편집·댓글 기능 구현은 확인되지 않는다. package.json에는 test script가 없다.

기존 frontend README 및 다른 문서에 있는 이전 초안의 모의 테스트·실 API·OAuth 관련 기록은 현재 기본 앱의 검증 결과가 아니다. [초기 구성 기록](source/REPOSITORY_SETUP.md)은 리셋된 기본 앱과 과거 구현을 구분한다. 기존 [CONTRACT_GAPS](source/CONTRACT_GAPS.md)는 API·화면 설계 차이의 참고이며 각 사실의 현재 유효성을 관련 작업에서 확인한다.

기존 GUIDELINES의 npm test는 현재 실행 가능한 명령이 아니며 package scripts가 기준이다. 폴더 이름은 초기 구성의 app/features/components/lib 및 feature 내부 api/components/hooks에 맞췄다. 이전 shared/model/ui 제안을 동시에 적용하지 않는다. 순수 기능 함수를 억지로 hook으로 만들지 않는다.

기존 SCREEN_SPEC의 /admin/...은 단일 사이트 형태의 목표 예시였다. 현재는 adminweb 별도 사이트로 계획하며 [관리자 화면 명세](../adminweb/SCREEN_SPEC.md)의 제안 경로를 따른다. 실제 hostname과 경로는 관리자 구현 시 확정한다.

## 3. 계약을 바꾸지 않고 진행할 수 있는 범위

공개 Post·Project 목록/상세, 실제 지원 필터·페이지, 공통 상태 표시, Naver 로그인·프로필·본인 목록 등 제공되는 API를 기반으로 작업할 수 있다. UI 디자인·코드 구조는 이 문서의 제안을 해당 Issue 범위에서 구체화한다.

블로그가 비어 있을 때 없는 메타데이터를 추측하거나 서버에 없는 상태·집계·필터를 추가하는 문제는 프론트 구현으로 숨기지 않는다. 필요한 계약 보완은 독립적인 사용자 목표와 완료 조건으로 제안한다.

## 4. 결정 기록 형식

변경할 때는 날짜·문제·기존 계약 또는 코드 근거·선택·영향·검증·남은 사항을 기록하고 PRD·API·보안 중 원본도 함께 갱신한다. 새 프로젝트에 적용할 일반 원칙은 common에, Pebble 고유 판단은 이곳에 둔다.

## 5. 2026-10-03 문서 점검

- 초기 구성의 폴더 명칭과 새 아키텍처 제안을 통일했다.
- ID 정밀도 보존과 서버 정렬을 명시하고 수치 비교 자체를 금지한 표현을 수정했다.
- PostService.thumbnailUrl·PostResponse와 MediaIntegrationTest.resourceViewsIssueUrlsOnlyWhilePublicAndPreserveLikeDecoration을 대조해 백엔드 API의 오래된 “항상 null” 설명을 바로잡았다. 테스트 코드를 확인했으며 이번 문서 점검에서 실행하지 않았다.
- SSR 미인증 404와 작성자 관리 경로, refresh/logout의 Set-Cookie 경합, Origin별 탭 조정 범위를 보완했다.

## 2026-10-03 홈 목 미리보기와 디자인 대조

Issue #2 / PR #3에 재현 가능한 dev:mock 도구를 추가했다. 일반 API 실패를 목 결과로 대체하지 않고 로컬 개발 실행에서만 사용한다. 21개 목 글·빈 목록·503·지연 상태를 지원한다. 홈에 목 데이터 안내를 표시한다.

처음 구현한 홍보 문구와 큰 여백은 디자인 문서의 확정 요소가 아니었다. 문서의 공개 탐색 제목으로 변경하고 글 탐색 메뉴·간결한 상단 공간을 반영했다. 로고는 기존 SVG 초안을 유지했다. 프로젝트·분류·로그인 메뉴, 검색·필터·보조 패널은 이후 기능이므로 현재 홈은 부분 구현으로 기록한다. 최종 시안 일치나 브랜드 확정으로 간주하지 않는다.
