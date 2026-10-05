# 운영 refresh 500과 중복 인증 안내 수정

2026-10-05 사용자 보고: POST /api/v1/auth/token/refresh 500, 프로필 로그인 복구 안내 중복.

## 원인과 변경

USER·관리자 refresh 전환 Lua가 TYPE을 호출하지만 초기 배포 Redis ACL에 TYPE이 빠져 있었다. 쿠키 없음과 존재하지 않는 대역 토큰은 전환 Lua에 도달하지 않아 401을 반환하고, 실제 토큰 회전 경로에는 권한 오류가 발생할 수 있다. 모든 인증 Lua 명령이 초기 ACL에 포함되는지 검사하는 test_redis_acl.py를 추가하고 초기 생성 코드에 type을 추가했다.

사용자가 users.acl 프로그램 내부 읽기·TYPE 권한만 추가·Redis 재시작을 명시적으로 승인했다. add-redis-type.py로 실제 운영 ACL에 +type만 추가했다. 비밀번호/해시와 다른 권한을 보존하고 파일 소유자·모드를 유지했다. 비밀 값은 출력하지 않았고 .env와 비밀번호 파일은 읽지 않았다. 해당 스크립트는 신규 승인 없이 다른 비밀 파일을 읽도록 확장하지 않는다. provision-secrets.py는 초기 설치용이며 기존 private 디렉터리가 있으면 중단한다. 이번에는 초기 생성기를 운영에서 다시 실행하지 않았다.

프론트에서 부가 탈퇴 영역은 프로필 영역의 로그인 안내를 재사용하도록 수정돼 있었다. 남아 있던 중복 로딩도 MemberGate fallback을 적용해 생략했다. 기존 500/응답 유실의 자동 refresh 재시도 금지와 회원 작업 권한 확인은 유지했다. 프론트 Workers 버전 0ce0e8bf-ba82-4459-9ba0-ea43a13fb987로 배포했다.

## 검증과 한계

- 초기 ACL과 실제 인증 Java/Lua redis.call 명령의 정적 대조 검사 성공.
- 로컬 Docker 실행은 사용할 수 없어 실패했다. 이후 Oracle의 별도 네트워크 없는 64MiB 임시 Redis 컨테이너로 검증했으며 테스트 후 제거했다. 운영 Redis의 실제 데이터와 비밀번호는 테스트에 사용하지 않았다.
- TYPE 없는 테스트 계정은 Lua TYPE에서 권한 오류, +type만 추가하면 성공. CONFIG와 허용 범위 밖의 키는 계속 NOPERM.
- 운영 Redis 재시작 후 존재하지 않는 대역 refresh 토큰은 401 INVALID_REFRESH_TOKEN 확인.
- Workers 빌드·타입 검사 성공. 실제 사용자 Naver 로그인 후 refresh 회전 성공은 해당 브라우저에서 최종 확인이 필요하다.
- 기존 ApiExceptionHandler는 예기치 않은 오류의 원인을 로그에 기록하지 않아, 보고된 개별 500의 traceId와 스택을 연결해 확정하지는 못했다. 이 기록은 확인된 권한 누락과 별도 재현 결과를 구분한다.
