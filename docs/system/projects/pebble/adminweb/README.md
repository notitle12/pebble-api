# Pebble 관리자 프론트 작업 기준

adminweb은 사용자 web과 분리된 관리자 운영 화면이고 같은 pebble-api를 사용한다. 사용자 설명으로 구성은 확인했으나 로컬 경로·프레임워크·구현 상태는 미확인이다.

## 작업별 읽을 문서

1. [프론트 요구사항](../frontend/PRD.md)의 관리자 범위와 [관리자 화면 명세](SCREEN_SPEC.md).
2. [프론트 아키텍처](../frontend/ARCHITECTURE.md)의 공통 책임·관리자 분리 절. Next.js별 구조는 사용자 web 전용이다.
3. [상태 관리](../frontend/STATE.md), [API 연동](../frontend/API.md), [인증·보안](../frontend/SECURITY.md)의 관련 절.
4. 배포는 [DEPLOYMENT](../frontend/DEPLOYMENT.md)의 관리자 항목, 검증은 [DEVELOPMENT](../frontend/DEVELOPMENT.md)의 공통 절과 실제 adminweb scripts.
5. 미확정 스택·경로는 [결정 사항](../frontend/DECISIONS.md)에 기록한다.

- 공통 프론트 기준과 Pebble SYSTEM을 읽고 실제 adminweb 저장소의 설정·문서·코드를 확인한다.
- 관리자 SPA와 Pages 또는 Workers 정적 배포를 우선 검토한다. 실제 기술 스택이나 배포 완료를 추정하지 않는다.
- 관리자 로그인·세션과 USER 로그인을 구분한다. MANAGER·MASTER 권한과 서버의 관리자 endpoint 계약을 따른다.
- 관리자 API 작업은 pebble-api/docs/API.md의 관련 관리자 절과 SECURITY.md를 읽는다. USER 댓글 작성·좋아요 권한을 관리자에게 제공하지 않는다.
- web의 화면 명세·Next.js 안내·npm 명령을 관리자 프로젝트에 그대로 강제하지 않는다.
- 프로젝트 경로가 확인되면 저장소 AGENTS.md에 공통 진입점만 연결하고 관리자 화면·검증 기준은 이 폴더의 문서로 관리한다.
