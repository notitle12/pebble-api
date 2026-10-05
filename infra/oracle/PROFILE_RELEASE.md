# 블로그 생성·프로필 배포 (2026-10-05)

API는 블로그명/닉네임/식별자 중복 확인, `_` 식별자, 사진과 이름의 원자 저장을 제공한다. 최초 SNS 닉네임 충돌에는 무작위 숫자를 붙이며 최종 고유 제약·회원 잠금·7일 이름 변경 제한은 유지한다.

V16은 handle CHECK를 확장하고 member.profile_image_storage_key와 제거 사진의 삭제 큐 트리거를 추가한다. 기존 마이그레이션을 수정하지 않았다. 사진에는 기존 정지 이미지 형식·크기·픽셀 검증 및 WebP 재인코딩, 비공개 R2/CDN signed URL을 적용한다.

개인 .env·운영 비밀 설정을 읽지 않고 임시 PostgreSQL 18/Redis, 자동 생성 인증키, 대역 R2로 백엔드 전체 478개 테스트를 실행했고 실패 0개다. 업로드 실패 시 생성 롤백과 orphan 작업, 중복·무권한·multipart 중복 필드, 사진 제거/회원 파기 삭제 큐를 검증했다. 실제 Oracle R2 쓰기는 이번 작업에서 아직 검증하지 않았다.

운영 DB는 마이그레이션 직전 `/opt/pebble/runtime/backups/pebble-20261005T054855Z.dump`로 백업했고 pg_restore --list로 구조를 확인했다. 복원 훈련·정기 백업 타이머 설치 완료를 의미하지 않는다.

Oracle staging에서 JAR만 포함한 ARM64 이미지 `pebble-api:profile-20261005`를 빌드했다. 이미지 manifest `sha256:07508fca00341cd2858ef561b8368ebbaf489041d094b207b05e7d91a6863829`. 운영 compose의 API 이미지 태그만 교체했고 이전 compose는 같은 backups 폴더에 보존했다. DB·Redis·비밀 값·방화벽은 변경하지 않았다. V16 success=true, categories 200, Guest 중복 검사 401, 운영 Origin 중복 검사 CORS GET 200을 확인했다.

외부 Issue/PR는 기존 승인 검토 제한으로 이번 작업에서 발급하지 않았다. 작업 기록과 로컬 커밋으로 변경을 보존한다. 실제 Naver 계정으로 생성/편집·R2 사진 저장 확인은 사용자의 로그인 상태에서 후속 검증이 필요하다.
