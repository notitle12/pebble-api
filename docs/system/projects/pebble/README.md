# Pebble 프로젝트 문서

이 폴더는 다른 서비스에 자동 적용하지 않는 Pebble 전용 구조·계약의 진입점이다.

- [전체 시스템 구조](SYSTEM.md): api·web·adminweb 분리, 인프라와 렌더링 방향.
- [백엔드 지침](backend/GUIDELINES.md): Java·Spring·Feature 책임·응답·Git 규칙.
- [프론트 문서](frontend/README.md): 요구사항·구조·상태·API 연동·인증·개발·배포 기준과 기존 사용자 화면 설계.
- [관리자 프론트 문서](adminweb/README.md): 별도 관리자 화면 명세, 책임과 현재 미확인 상태.
- [공유 Git 작업 순서](GIT_WORKFLOW.md): pebble-api와 pebble-web의 Issue → `dev` 작업 브랜치 → push → PR → 검토·CI → 병합 절차.

api의 PRD·API·DB·ARCHITECTURE·SECURITY·DEVELOPMENT는 기존 pebble-api/docs에 보존한다. web의 화면·디자인·계약 차이 문서는 기존 pebble-web/docs/frontend에 보존한다. 공통 규칙으로 옮기기 위해 제품 계약을 일반화하지 않는다.
