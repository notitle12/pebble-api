# Pebble 시스템 구조와 개발 기준

작성일: 2026-10-03
상태: 현재 구조 및 논의한 설계 방향 정리. 실제 배포 완료를 의미하지 않는다.

## 1. 현재 프로젝트 구성

Pebble은 api, web, adminweb으로 구분한다. API는 일반 회원과 관리자 기능을 함께 제공하고, 프론트엔드만 사용자 서비스와 관리자 서비스로 분리한다.

| 프로젝트 | 책임 | 확인 상태 |
|---|---|---|
| api | 인증, 회원, 관리자 운영, 콘텐츠, DB, 미디어 정책 | 로컬 pebble-api 확인 |
| web | 공개 블로그·프로젝트 탐색과 회원 기능 | 로컬 pebble-web 및 Next.js 사용 확인 |
| adminweb | 관리자 로그인과 운영 화면 | 사용자 설명으로 구성 확인, 로컬 경로·기술 스택 미확인 |

프론트 분리는 관리자 API나 인증 시스템을 별도 백엔드로 분리한다는 의미가 아니다. 관리자 권한과 리소스 접근의 최종 검증은 api가 수행한다.

## 2. 인프라 현황과 배포 방향

- api: Oracle Cloud 배포 예정.
- 도메인: Cloudflare에서 준비됨. 실제 서비스 hostname과 DNS 설정은 별도 확인한다.
- 미디어 버킷: Cloudflare R2 사용 중.
- web: Cloudflare Workers 배포를 우선 검토한다. SSR·프레임워크 호환성과 로그인·이미지 조회 검증 후 배포 구성을 확정한다.
- adminweb: SPA 방향을 검토한다. Pages 또는 Workers 정적 배포 중 실제 프로젝트에 맞춰 선택한다.
- 상업 운영 가능성을 고려한다. 무료 플랜의 이용 조건과 실행 한도를 확인하며 특정 업체의 무료 사용을 영구 전제로 삼지 않는다.

예시 hostname은 사용자 web에 example.com, 관리자 web에 admin.example.com, API에 api.example.com이다. 이는 실제 도메인이 아니다.

## 3. 렌더링 설계 방향

- 공개 게시글·프로젝트 소개·블로그는 본문과 메타데이터를 서버에서 렌더링하는 SSR을 우선 검토한다.
- 댓글 작성·좋아요·편집기·내 설정은 브라우저에서 입력과 상호작용을 처리한다.
- adminweb은 검색 노출보다 운영 기능을 중심으로 하는 SPA를 우선 검토한다.
- 사용자 web도 SSR과 클라이언트 렌더링을 함께 사용한다. 모든 사용자 화면을 SSR로 고정하지 않는다.
- 프레임워크, 버전, Workers 배포 도구와 렌더링 구현의 현재 상태는 각 프로젝트의 코드·설정을 확인한다.

## 4. 책임 경계

- api는 제품 정책, 인증·인가, 상태 전이, 트랜잭션, 저장 및 삭제 정책을 소유한다.
- web과 adminweb은 화면, 입력 안내, API 호출과 UI 상태를 담당한다.
- Post와 Project는 별도 기능으로 유지한다. 공통 UI 때문에 하나의 도메인으로 합치지 않는다.
- 회원과 관리자 인증 상태를 혼합하지 않는다. 관리자 역할이 일반 회원 쓰기 권한을 포함한다고 가정하지 않는다.
- 프론트엔드는 기능별 구현을 모으고 공통 UI·HTTP 같은 기술 책임만 공유한다. 기존 구조로 충분하면 계층이나 공통 패키지를 추가하지 않는다.

## 5. 인증·API·미디어 계약

세부 계약은 api의 docs/API.md와 docs/SECURITY.md를 따른다.

- Access Token은 메모리에 두고 Bearer Header로 전달한다. Refresh Token은 HttpOnly Cookie를 사용한다.
- 쿠키를 사용하는 로그인·갱신 요청은 credentials를 포함하고 정확한 프론트 Origin을 API에 등록한다.
- 현재 Secure·SameSite=Lax 정책에 맞는 HTTPS와 도메인 구성을 검증한다.
- 동시 refresh를 단일화하고 다중 탭 경쟁을 고려한다. 응답 유실을 이유로 refresh를 무조건 재시도하거나 비활성 세션을 주기적으로 연장하지 않는다.
- 서버 렌더링은 브라우저 메모리의 Access Token을 직접 사용할 수 없다. 공개 조회와 회원별 조회를 구분한다. BFF 도입이나 토큰 전달 변경은 별도 계약 검토가 필요하다.
- API ID는 문자열로 다룬다. 공통 성공·오류 응답과 PATCH의 생략·null 규칙을 따른다.
- 회원별 데이터·비밀 댓글·소유자 전용 정보는 공용 캐시에 넣지 않는다. 로그인 상태 변경 시 관련 브라우저 캐시를 정리한다.
- 공개 콘텐츠의 숨김·차단·탈퇴 정책에 맞는 캐시 만료·무효화 방식을 검토한다.
- api가 이미지 검증·변환·R2 저장·권한 검사·signed URL 발급·삭제를 담당하는 기존 흐름을 유지한다.
- 프론트를 Workers에 배포하는 이유만으로 미디어 버킷 직접 접근이나 공개 URL을 추가하지 않는다.

## 6. 문서의 기준과 작업 시작 절차

이 문서는 프로젝트 구성과 공통 설계 방향을 정리한다. 기존 세부 계약을 대체하지 않는다.

| 판단할 내용 | 기준 |
|---|---|
| 전체 프로젝트 분리와 인프라 방향 | 이 문서 |
| 제품 범위 | pebble-api/docs/PRD.md |
| API·오류·DTO 계약 | pebble-api/docs/API.md |
| 인증·인가·토큰 | pebble-api/docs/SECURITY.md |
| DB·저장 정책 | pebble-api/docs/DB.md |
| 백엔드 책임과 의존 방향 | pebble-api/docs/ARCHITECTURE.md |
| 개발·검증·Git 절차 | 공통 진입점과 영역별 지침 및 개발 문서 |
| 사용자 화면 설계 | pebble-web/docs/frontend/README.md와 관련 문서 |

에이전트는 새 작업 시작 시 Git 상태, 프로젝트 AGENTS.md, 이 문서를 확인한다. 이어서 작업과 관련된 세부 문서 절과 기존 코드를 읽는다. 같은 작업 도중 변경되지 않은 전체 문서를 매번 재출력하지 않는다.

문서와 실제 구현이 다르면 현재 사실과 차이를 보고한다. 설계 제안을 이미 구현·배포된 상태로 표현하지 않는다. 미확정 항목을 임의 확정하거나 프론트에 맞추기 위해 보안 계약을 완화하지 않는다.

구조·배포·인증 경계를 바꾼 작업은 관련 계약 문서와 이 문서를 함께 갱신한다. 비밀 값·토큰·실제 자격 증명은 문서에 넣지 않는다.

## 7. 문서 확인과 Obsidian 연결

공통 읽기 순서는 ../../AGENTS.md에서 관리한다. 백엔드 작업은 backend/GUIDELINES.md, 사용자 프론트 작업은 frontend/README.md, 관리자 작업은 adminweb/README.md를 따른다. API·인증·미디어 계약은 필요한 원본 문서 절을 확인한다.

원본은 pebble-api/docs/system/projects/pebble/SYSTEM.md이며 Obsidian에서는 projects/pebble/SYSTEM.md로 접근한다. Obsidian AGENTS.md·common·projects는 동일 원본을 연결한다. 심볼릭 링크의 UI 표시와 외부 동기화 지원은 별도 확인한다.

api와 web 저장소의 AGENTS.md는 공통 진입점만 연결한다. adminweb은 로컬 경로가 확인되지 않아 저장소 연결이 남아 있다.

## 이미지 CDN 구현 후속 (2026-10-04)

선택적 이미지 전용 Worker를 pebble-api/infra/image-worker에 추가했다. 프론트 API의 기존 url/thumbnailUrl 계약을 유지하고 api가 공개 판정 후 발급한 15분 HMAC 서명을 Worker가 캐시 조회 전 검증한다. 비공개 R2와 기존 잔여 15분 접근 정책을 유지하며 프론트 Worker·사용자 로그인 JWT와 이미지 서명 키를 분리한다. CDN 설정은 기본 비활성이다. 2026-10-04 도메인 소유 Cloudflare 계정의 기존 비공개 pebble-media 버킷에 이미지 Worker와 images.pebble-log.com Custom Domain을 배포하고 서명 키를 등록했다. 실제 HTTPS 캐시 MISS→HIT 및 만료·변조 차단을 검증하고 임시 객체를 삭제했다. 기존 보호된 R2 자격 증명과 동일 CDN 서명 키를 현재 8081 로컬 API에 적용했다. PNG 업로드→WebP 변환→실제 R2 저장→API 발급 CDN URL의 200·HIT, 비공개 전환 시 새 URL 미발급을 확인했고 테스트 객체는 삭제했다. 브라우저 공개 글 상세에서도 CDN 이미지가 정상 디코딩되어 보이는 것을 확인했다. Oracle 배포·실제 Naver 인증·브라우저 작성기의 파일 선택 동작은 후속 검증이다. 배포와 검증의 세부 기준은 pebble-api/infra/image-worker/README.md 및 docs/SECURITY.md를 따른다.

## Oracle 배포 준비 후속 (2026-10-04)

사용자가 제공한 Ubuntu 24.04 ARM64 서버에 지정 SSH 개인 키로 접속했다. Docker Engine 29.8.2/Compose 5.6.0을 공식 저장소에서 설치하고 비밀이 제외된 ARM64 API 이미지를 전송·등록했다. 전송 체크섬 일치와 서버 컨테이너의 비루트 Java 실행을 확인했다. 운영 DB·Redis·API 서비스 및 api.pebble-log.com DNS/HTTPS 연결은 아직 진행하지 않았다. 준비 상태와 실행 절차는 pebble-api/infra/oracle/README.md를 따른다.
