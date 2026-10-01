# 다음 작업 인계

- 기준 브랜치: dev. AGENTS.md와 작업에 필요한 문서 절만 확인한다.
- 완료: 회원·Naver 식별자 저장, Naver 로그인, RS256 Access JWT·Redis 초기 Refresh 세션 발급.
- 이슈 #15: 가이드라인 복원, 공통 오류 규격, JWT 필수 claim 검증, DTO·생성자 정비.
- 다음 기능: Refresh 회전·재사용 탐지·로그아웃을 한 Issue·PR로 구현한다.
- 관련 계약: SECURITY.md 7~9절과 API.md의 Refresh·로그아웃 endpoint. Cookie 요청 CSRF·Origin 방어와 CORS를 함께 다룬다.
- 출시 전 남음: JWT 키 교체, pepper 버전·교체, 로그인 속도 제한·보안 감사.
- 실제 Naver 앱·브라우저 로그인은 자격 증명 설정 후 검증이 필요하다.
- 비밀 값은 환경변수로 제공한다. 키·pepper·실제 자격 증명을 커밋하지 않는다.
- 작업 범위와 Git 상태를 확인하고 dev에서 작업 브랜치를 분기한다. 필요한 테스트와 CI를 확인해 dev에 통합한다.
