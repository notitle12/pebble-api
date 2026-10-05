# Pebble 프론트엔드 아키텍처

작성일: 2026-10-03. 목표 구조 제안이며 기존 코드를 이 구조로 전면 변경하라는 지침이 아니다.

## 1. 백엔드와의 대응

| 백엔드 책임 | 프론트에서 가까운 책임 | 차이 |
|---|---|---|
| presentation: HTTP DTO·Controller | api: endpoint·요청·응답 타입 | 실제 HTTP 처리는 공통 클라이언트 재사용 |
| application: 유스케이스·트랜잭션 | hooks·기능 함수: 폼·조회·저장 흐름 | 프론트는 서버 트랜잭션·권한을 대신하지 않음 |
| domain: 핵심 업무 규칙 | UI에 필요한 타입·입력 안내 | DB Entity와 업무 규칙을 복제하지 않음 |
| infrastructure: DB·Redis·외부 API | lib의 공통 HTTP 등 기술 구현 | 브라우저가 DB·Redis·R2 credential에 직접 접근하지 않음 |
| HTTP에는 없는 화면 책임 | components·app | UI 표시와 페이지 조립 |

이름이 대응한다고 계층을 네 개씩 만들 필요는 없다. 기능의 책임을 가까이 모으되 실제 코드가 있는 폴더만 만든다.

## 2. 사용자 web 목표 구조

```text
src/
├── app/                  # URL·레이아웃·페이지 조립
├── features/
│   ├── auth/
│   ├── member/
│   ├── post/
│   │   ├── api/          # 요청 함수·계약 타입
│   │   ├── components/   # 카드·상세·편집기
│   │   └── hooks/        # 필요한 조회·저장·폼 상태
│   ├── project/
│   ├── category/
│   ├── tag/
│   ├── board/
│   ├── comment/
│   └── like/
├── components/           # 기능 공통 UI·레이아웃
└── lib/                  # 공통 HTTP·오류·설정·서식 처리
```

현재 작업 브랜치에는 src/app의 공개 글/상세/프로젝트 라우트, features/post·project·tag, components/site-header와 lib/public-api·list-query가 있다. dev 통합 전의 구현 상태와 검증 범위는 README 및 기능별 인계 문서를 따른다. 위 구조는 [기존 초기 구성](source/REPOSITORY_SETUP.md)의 features·components·hooks·lib 이름을 유지한다. 이전 초안의 shared·model·ui 구조는 대안이었으며 별도 이전 계획 없이 혼용하지 않는다. React와 무관한 순수 기능 함수는 hook으로 만들지 않고 해당 feature의 파일에 둔다. 폴더 모양을 맞추기 위한 리팩터링을 선행하지 않는다. 모든 feature에 index.ts나 mapper·repository를 의무적으로 만들지 않는다.

## 3. 의존 방향과 화면 조립

app은 features와 공통 components·lib를 조립한다. features는 해당 기능 코드와 공통 components·lib를 사용한다. 공통 components·lib는 feature나 app에 의존하지 않는다. feature 간 순환 참조를 만들지 않고 필요한 계약만 제공한다.

게시글 상세 페이지에서 post 본문, comment 목록, like 버튼을 대상 ID로 연결한다. comment와 like에 target kind와 ID를 전달하고 대상별 API를 사용한다. Post와 Project의 폼을 하나의 ContentEditor로 합치지 않는다. 태그 선택기·버튼처럼 실제로 같은 UI는 재사용한다.

서버 데이터 조회 모듈은 브라우저 전용 인증 함수·hook을 호출하지 않는다. Server Component가 명시적인 Client Component를 import해 화면에 조립하는 것은 허용한다. 서버 전용 코드와 클라이언트 진입점을 명시적으로 분리해 번들에 비밀이나 서버 전용 의존성이 들어가지 않도록 한다.

## 4. 서버와 브라우저의 역할

| 화면 요소 | 제안 실행 위치 | 이유 |
|---|---|---|
| 공개 글·Project 본문과 메타데이터 | 서버 | 첫 HTML에 실제 내용을 제공 |
| 검색 입력·페이지 이동 | 브라우저 상호작용 + URL | 공유 가능한 조건과 탐색 |
| 좋아요·댓글 작성·내 상태 | 브라우저 | 메모리 Access Token과 상호작용 |
| 편집기·설정·내 목록 | 인증 복구 후 브라우저 | 개인 데이터와 폼 상태 |
| 관리자 전체 화면 | 별도 adminweb SPA | 운영 중심, 공개 SEO 불필요 |

Server Component는 서버에서 실행되는 컴포넌트, Client Component는 상태·이벤트·브라우저 API가 필요한 경계다. Client Component도 첫 HTML에 렌더링될 수 있으므로 use client를 붙였다는 이유만으로 SSR이 없다고 해석하지 않는다. SSR·빌드 시 정적 생성·브라우저 렌더링은 별개다. 공개 콘텐츠가 빌드 시점에 고정되지 않도록 실제 데이터 조회·캐시 정책을 확인한다.

현재 Access Token은 브라우저 메모리에만 있다. 서버의 최초 미인증 조회에 likedByMe=false가 와도 이를 로그인 회원의 최종 상태로 쓰지 않는다. 인증 복구 뒤 필요한 데이터를 별도 재조회한다. 비밀 댓글·본인 숨김 글을 서버 공용 응답에 섞지 않는다.

공개 URL의 미인증 조회가 404라면 타인에게 미존재로 표시한다. 작성자의 숨김·차단 글은 /me 등 인증 후 조회하는 관리 경로에서 접근하도록 구성한다. 공개 SSR에서 바로 notFound로 종료한 페이지가 나중에 브라우저 인증만으로 자동 복구된다고 가정하지 않는다. 회원 전용 내용·제목을 공개 metadata에 넣지 않는다. 서버 연결 실패·5xx를 404로 변환하지 않는다.

## 5. 상태와 라이브러리

[STATE](STATE.md)에 상태 소유권·캐시·저장 흐름을 정의한다. 현재 의존성은 Next.js·React·TypeScript다. 초기에는 fetch와 React 상태로 필요한 흐름을 구성할 수 있다. 조회·무효화·동시 요청 관리가 반복될 때 TanStack Query, 복잡한 폼에는 폼 도구를 검토한다. 새 라이브러리를 설치했다고 가정하거나 store를 여러 개 기본 도입하지 않는다.

## 6. 관리자 분리

adminweb에는 같은 공통 책임 구분을 적용하되 Next.js나 사용자 web의 라우트를 강제하지 않는다. auth, content-moderation, member-management, classification, admin-account 등 실제 운영 기능을 가까이 모은다. 사용자·관리자 간 공통 코드를 패키지로 분리하는 것은 중복·배포 결합의 이득이 확인될 때 판단한다. 서버 권한·쿠키·토큰 정책은 동일 API 계약을 따른다.

## 7. 설치된 프레임워크 문서

web의 Next.js 버전별 사용법은 node_modules/next/dist/docs의 project-structure, server-and-client-components, fetching-data, metadata-and-og-images 관련 절을 읽는다. 동적 params·fetch 캐시·라우팅 API를 과거 버전 기억으로 구현하지 않는다. Workers 배포 도구와 호환성은 [DEPLOYMENT](DEPLOYMENT.md)에서 별도 검증한다.
