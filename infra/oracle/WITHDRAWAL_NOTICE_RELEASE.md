# 탈퇴 예약 안내 개선 (2026-10-05)

Naver 로그인에서 본인 재인증·일회용 state 확인을 통과한 탈퇴 대기 회원에게만 `WITHDRAWAL_PENDING` 409의 표준 `error.details` 배열로 `{field: "withdrawalScheduledAt", reason: "<ISO Instant>"}`를 전달한다. 최신 회원 상태·시각을 읽기 잠금 안에서 확인하며 새 토큰 발급과 자동 취소는 하지 않는다. Refresh·관리자 상태 변경 응답은 변경하지 않았다.

프론트는 한국 시간 `YYYY-MM-DD HH:MM:SS`를 표시하고 단일 탈퇴 예약 취소 버튼으로 기존 Naver 재인증을 시작한다. 설정의 중복 취소 안내 링크를 제거하고 버튼 간격을 정리했다.

운영 비밀 파일 없이 새 루프백 PostgreSQL/Redis와 임시 인증키로 NaverAuthIntegrationTest 12개·UserSessionIntegrationTest 7개를 실행하여 실패 0개를 확인했다. 백엔드 이미지는 `pebble-api:withdrawal-time-20261005`로 Oracle에 배포했다. DB 스키마·비밀 값·방화벽 변경 없이 API 컨테이너만 갱신했고 공개 categories 200을 확인했다.

로컬 대역 브라우저로 예정 시각과 단일 취소 버튼, 설정 중복 제거 및 버튼 간격을 확인했다. 실제 운영 회원 탈퇴/취소는 수행하지 않았다. 사용자의 재로그인 확인이 남아 있다.
