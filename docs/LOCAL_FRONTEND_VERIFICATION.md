# 로컬 실제 API로 프론트 검증

Issue #58 · 2026-10-03 · `chore/58-local-preview-data`

배포 없이 로컬 Next.js → Spring Boot → PostgreSQL 흐름을 확인한다. `dev:mock`은 로딩·오류 등 상태를 재현할 때 계속 사용하고, 정상 콘텐츠의 계약·화면은 아래 실제 API 흐름으로 확인한다.

## 1. 준비

기존 DEVELOPMENT.md 절차로 로컬 Compose PostgreSQL·Redis와 API를 실행한다. API 주소는 `http://127.0.0.1:8080/api/v1`이다. 테스트 데이터 도구는 현재 개발 Compose의 `pebble-api-postgres-1`, `postgres:17.11-alpine`, DB/사용자 `pebble`에만 연결한다. Docker endpoint/context가 Unix socket인지 확인하며 외부 DB URL이나 비밀번호 입력 옵션을 제공하지 않는다. Compose 이름·이미지·DB 설정이 다르면 자동 우회하지 않고 종료한다.

api 루트에서 실행한다.

```sh
bash scripts/local-preview.sh seed
node scripts/verify-local-preview.mjs
```

검증 스크립트는 Node 22 이상이 필요하다. 고정 로컬 주소에 인증 없이 GET만 요청한다. API 결과의 정확한 개수 검증은 기존 글·프로젝트가 없는 초기 개발 DB를 기준으로 한다. 다른 콘텐츠가 있는 DB에서는 fixture 생성은 가능하지만 전체 개수 기대값을 별도로 맞춰야 한다.

## 2. 데이터 범위

| 항목 | 내용 |
|---|---|
| 작성자 | `local-preview`, 닉네임 `로컬검증`, 블로그 `로컬 연동 검증` |
| 공개 글 | 24개: Backend/Spring Boot 21개, Frontend/React 3개 |
| 비공개 글 | HIDDEN 1개, DELETED 1개; 공개 조회에서 제외 |
| 본문 | TEXT 한글·HTML 문자열 + JAVA CODE 2개 블록 |
| 프로젝트 | PUBLIC/COMPLETED 1개, PUBLIC/IN_PROGRESS 1개, HIDDEN 1개 |
| 관련 글 | 첫 프로젝트 21개, 두 번째 3개; 페이지 이동 확인 |
| 하위 데이터 | 첫 프로젝트 주요 기능 1개·GitHub 링크 1개 |

기존 초기 Backend/Frontend 분류·Spring Boot/React 태그를 참조한다. 누락되면 전체 트랜잭션을 롤백한다. 분류·태그·기존 회원은 수정하지 않는다. OAuth identity, 로그인 credential, 관리자, R2 파일을 만들지 않는다. 이 도구는 공개 조회 fixture를 직접 SQL로 저장하므로 회원 쓰기 API·OAuth·인가 검증을 대신하지 않는다.

예약 ID는 member `910000000000001000`, post `910000000000001101..1126`, post_block `910000000000002002..2053`, project `910000000000003001..3003`, feature `910000000000004001`, link `910000000000005001`이다. 서로 다른 테이블의 ID 범위다. 전용 작성자·예약 ID의 충돌을 검사하고, advisory transaction lock과 단일 트랜잭션을 사용한다. 재실행은 `ON CONFLICT DO NOTHING`으로 기존 fixture를 보존한다. 편집 후 원본 fixture로 복원하려면 clean 후 seed한다.

## 3. 프론트 실행

목 프론트가 실행 중이면 해당 프로세스를 Ctrl+C로 종료한다. 동일 checkout의 Next 개발 서버를 동시에 실행하지 않는다. `pebble-web` 루트에서 Node 22로 실행한다.

```sh
NEXT_PUBLIC_API_BASE_URL=http://127.0.0.1:8080/api/v1 PEBBLE_PREVIEW_MODE=local \
  node node_modules/next/dist/bin/next dev --hostname 127.0.0.1 --port 3100
```

기존 로컬 환경변수 파일을 수정하지 않고 실행 명령에서 API를 지정한다. `PEBBLE_PREVIEW_MODE=local`은 목 표시를 제거하며 별도의 인증/보안 동작을 추가하지 않는다. 글·프로젝트 제목의 `[로컬 테스트]`가 fixture임을 명시한다. 공개 SSR GET은 CORS나 OAuth 로그인 없이 동작한다.

- 홈: `http://127.0.0.1:3100/`
- 글 상세: `/blogs/local-preview/posts/integration-1`
- 프로젝트 상세: `/projects/910000000000003001`
- 필터: `/?q=Spring&tagId=893467413652345599&categoryId=893467413652345593`

## 4. 정리와 검증 결과

```sh
bash scripts/local-preview.sh clean
```

clean은 전용 작성자와 예약 ID가 모두 일치하는 글·프로젝트·회원만 제거한다. 다른 소유자와 충돌하거나 외부 참조가 생기면 오류로 롤백한다. 기존 회원·분류·태그 및 DB 볼륨은 유지한다. 실제 R2 미디어를 붙인 fixture를 삭제하면 기존 DB trigger가 미디어 삭제 job을 생성할 수 있으므로 이 fixture에는 미디어를 붙이지 않는다.

확인 결과:

- seed 2회: 회원 2명, 글 26개, 프로젝트 3개, 본문 52개 유지. 중복 0.
- clean: 기존 회원 1명 보존, 글·프로젝트 0개. seed로 미리보기 데이터 재준비.
- 실제 API 검증 6개 통과: 공개 목록 20/4개, 검색+태그+분류 21개, 충돌 조건 0개, TEXT/CODE 상세, 숨김·삭제 글과 비공개 프로젝트 404, 프로젝트 상태/검색/상세, 관련 글 20/1개.
- 브라우저: 실제 홈 24개·2번째 페이지 4개, 상세의 HTML 문자열 안전 표시·코드 복사 성공, Backend 탐색→Spring Boot 조합 검색→2번째 페이지 조건 유지, 프로젝트 목록 2개·상세·관련 글 2번째 페이지 확인.
- 예약 ID 충돌 시 오류·전체 롤백 및 기존 fixture 이름 보존, TCP Docker endpoint 거부, 잘못된 실행 모드 거부 확인.
- `bash -n scripts/local-preview.sh` 통과. Java/Next 애플리케이션 코드는 변경하지 않았으므로 기존 단위 테스트·빌드를 반복하지 않았다.

현재 프론트 미리보기 3100은 실제 로컬 API 8080을 사용한다. 정상 공개 조회의 실제 연동 검증을 완료했다. OAuth·회원 쓰기·R2·Workers 배포는 이 작업 범위가 아니며 해당 기능을 개발할 때 별도로 검증한다.
