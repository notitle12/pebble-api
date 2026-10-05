# Pebble 프론트엔드 API 연동

작성일: 2026-10-03. [백엔드 API](../backend/contracts/API.md)가 외부 계약 원본이다. 이 문서는 이를 프론트에서 호출·표시하는 방법을 설명하고 새로운 endpoint를 정의하지 않는다.

## 1. 공통 HTTP 처리

공통 HTTP 계층은 base URL, 헤더, 응답 해석, 오류 정규화와 네트워크 오류 구분을 담당한다. 기능별 api 모듈은 endpoint와 DTO를 소유한다. 로그인·refresh의 동시성 정책은 auth가 조정한다. 모든 HTTP 함수에 독립 refresh 재시도를 복제하지 않는다.

성공 JSON은 data 래퍼를 해석한다. 204는 본문이 없으므로 JSON 파싱을 하지 않는다. 생성 201·탈퇴 예약 202도 성공으로 처리한다. 204 이후 필요한 최신 수치나 목록은 별도 조회한다.

## 2. 타입과 입력

- BIGINT·TSID ID는 string으로 유지하고 Number로 변환하지 않는다. 목록 순서는 서버 응답을 따른다. ID의 수치 비교가 꼭 필요하면 BigInt 등 정밀도를 보존하는 방법을 사용하고 JSON 경계에서는 문자열을 유지한다.
- UTC 시각은 계약 문자열로 보관하고 표시할 때 사용자 시간대로 변환한다. 날짜만 있는 YYYY-MM-DD를 UTC timestamp처럼 변환해 날짜를 이동시키지 않는다.
- 목록과 상세 DTO는 다를 수 있다. Post 목록에 blocks, Project 목록에 media·주요 기능·상세 설명이 있다고 가정하지 않는다.
- PATCH 생략은 유지, nullable null은 제거다. undefined를 null로 일괄 바꾸지 않는다. 수정 불가 필드·알 수 없는 필드를 보내지 않는다.
- 배열 변경은 전체 교체다. 클라이언트의 부분 항목 수정 의도를 서버 지원 없이 부분 PATCH로 꾸미지 않는다.
- TypeScript 타입은 런타임 응답 검증이 아니다. 외부 값의 필수 형태를 확인하고 필요한 경우에만 기존 도구 또는 검증 라이브러리를 검토한다.

## 3. 화면과 API의 관계

경로 앞에는 /api/v1이 붙는다. 자세한 화면 라우트는 [기존 화면 명세](source/SCREEN_SPEC.md)를 읽는다.

| 화면 기능 | 백엔드 API | 프론트 처리 |
|---|---|---|
| 공개 Post 목록·검색 | GET /posts, /posts/search | 지원 필터·페이지를 URL과 연결 |
| 공개 Post 상세 | GET /blogs/{handle}/posts/{postKey} | 공개 링크와 canonical은 handle+slug/글 번호 |
| Project 목록·검색·상세 | GET /projects, /projects/search, /projects/{id} | 목록·상세 필드 구분 |
| 내 콘텐츠·Board | GET /members/me/posts, /members/me/projects, /members/me/boards | USER Bearer, 본인 데이터 |
| 내 프로필 | GET /members/me, POST/PATCH /members/me/profile | 최초 설정과 변경 폼 구분 |
| 댓글·좋아요 | /posts/{id}/comments·/like 또는 /projects/{id}/comments·/like | 대상 kind 명시, 댓글 비밀 접근 범위 반영 |
| 분류 | GET /categories, /tags | 공통 분류·태그와 개인 Board 구분 |
| 미디어 | Post 썸네일 PUT/DELETE, Project media POST/PATCH/DELETE | multipart 필드와 권한 확인 |
| 관리자 운영 | /admin 아래 계약상 경로 | 별도 관리자 세션·목록 DTO·지원 query |

개인 블로그의 handle을 Naver 식별자나 내부 회원 ID로 바꾸지 않는다. Post 수정·삭제는 응답의 내부 ID를 사용한다. Board·Project 조건을 전역 Post 검색에 보내는 등 지원하지 않는 필터를 추가하지 않는다.

## 4. 오류 분기

| 상황 | 표시·처리 |
|---|---|
| 400 입력 오류 | details를 해당 필드에 표시; 필드 없는 오류는 폼/화면 안내 |
| 401 인증 오류 | auth 흐름으로 처리, 허용된 복구가 끝나면 로그인 안내 |
| 403 역할·상태 거부 | code를 기준으로 역할·정지·탈퇴 상태 안내; 자동 refresh 반복 금지 |
| 404 없음 또는 조회 불가 | 같은 미존재 화면; 타인의 비공개·차단 존재를 추측하지 않음 |
| 409 충돌 | 이름 중복·쿨타임·관계 충돌 등을 code와 해당 계약대로 안내 |
| 413·415 | 크기·형식 안내; 서버의 실제 이미지 검증이 최종 기준 |
| 429 | 요청 제한 안내; 계약에서 제공하는 대기 정보가 있을 때만 사용 |
| 5xx·네트워크 실패 | 입력 보존과 재시도 안내; 인증 실패·빈 결과와 구분 |

error.code로 분기하고 message와 details를 표시한다. traceId는 오류 문의에 연결하며 토큰·본문·개인정보를 자동 로깅하지 않는다. 문서의 대표 오류 목록과 endpoint 상세가 다르면 실제 ErrorCode·테스트를 대조해 [결정 사항](DECISIONS.md)에 기록한다. 없는 code를 발명하지 않는다.

## 5. 재시도와 요청 취소

일반 조회는 취소·제한된 재시도를 검토할 수 있다. 생성·삭제·프로필 변경·업로드를 네트워크 오류만으로 무조건 재전송하지 않는다. 일회용 OAuth code와 refresh는 자동 재시도하지 않는다. 인증 만료 뒤 원 요청을 재전송할 때도 이미 처리되었을 가능성과 본문 재사용 가능성을 검토한다.

FormData 전송은 브라우저가 multipart boundary를 설정하도록 한다. 이미지 MIME과 크기는 사전 안내일 뿐 서버의 실제 형식·픽셀 검증을 대체하지 않는다.

## 6. 미디어의 시간과 상태

R2 credential이나 저장 키를 브라우저에 전달하지 않는다. signed GET URL은 15분 유효하고, 기존 URL은 숨김·차단 뒤에도 만료 전까지 사용할 수 있다. 새 발급은 서버의 공개·소유권 정책을 따른다. 이미 내려받은 이미지는 만료 시각에 화면에서 사라지는 것이 아니다.

HIDDEN·차단 소유자 미디어 관리 응답의 url·thumbnailUrl은 null일 수 있다. DB 참조 삭제와 R2 객체 회수 완료는 다른 시점이다. Post thumbnailUrl은 저장소가 활성화되고 썸네일이 있으며 공개 조건을 만족할 때 signed URL을 반환한다. 이미지가 없거나 발급 조건을 만족하지 않으면 null이다. Project 목록은 media를 생략하므로 Post 썸네일 계약과 구분한다.
