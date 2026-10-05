# Pebble 관리자 화면 명세

작성일: 2026-10-03. 사용자 web과 별도 사이트로 배포할 adminweb의 목표 설계다. 저장소·프레임워크·실제 화면 구현은 미확인이다. 프론트 경로는 제안이고 API 경로는 기존 계약을 참조한다.

## 1. 책임과 기준

MANAGER는 콘텐츠·댓글·회원·분류 운영을 담당하고 MASTER는 관리자 계정 운영도 담당한다. USER 댓글·좋아요·콘텐츠 작성 기능을 관리자에게 제공하지 않는다.

API의 최종 기준은 [백엔드 API](../backend/contracts/API.md) 5.7·6.2·6.5·6.6·6.7절이고 인증은 [SECURITY](../backend/contracts/SECURITY.md)다. 프론트의 공통 연동·상태·보안 기준은 [API](../frontend/API.md), [STATE](../frontend/STATE.md), [SECURITY](../frontend/SECURITY.md)를 따른다. web의 Next.js 구현 방식을 강제하지 않는다.

## 2. 목표 화면과 라우트

아래 화면 경로는 admin hostname 안의 경로다. 사용자 web에 같은 관리자 페이지를 중복 구현하지 않는다. API 경로는 /api/v1 기준이다.

| 화면 | 제안 경로 | 역할 | API와 주요 동작 |
|---|---|---|---|
| 로그인 | /login | Guest | POST /admin/auth/login, 관리자 ID·비밀번호 |
| 운영 진입 | / | MANAGER·MASTER | 실제 운영 목록 링크, 제공되지 않는 통계 없음 |
| 게시글 목록·검수 | /posts, /posts/{id} | MANAGER·MASTER | GET /admin/posts 및 상세, PUT/DELETE block, DELETE 콘텐츠 |
| 프로젝트 목록·검수 | /projects, /projects/{id} | MANAGER·MASTER | GET /admin/projects 및 상세, 차단·해제·강제 삭제 |
| 게시글 댓글 운영 | /post-comments | MANAGER·MASTER | GET /admin/post-comments, DELETE /admin/post-comments/{id} |
| 프로젝트 댓글 운영 | /project-comments | MANAGER·MASTER | GET /admin/project-comments, DELETE /admin/project-comments/{id} |
| 회원 목록·상태 | /members, /members/{id} | MANAGER·MASTER | GET /admin/members 및 상세, PATCH status |
| Category 운영 | /categories | MANAGER·MASTER | GET/POST /admin/categories, PATCH /admin/categories/{id} |
| Tag 운영 | /tags | MANAGER·MASTER | GET/POST /admin/tags, PATCH /admin/tags/{id} |
| 관리자 계정 | /admin-accounts | MASTER | GET/POST /admin/admin-accounts, PATCH /admin/admin-accounts/{id}/status |

관리자 댓글 단일 상세 GET·작성·수정·복원은 제공하지 않는다. Category·Tag 상세 GET·물리 삭제도 제공하지 않는다. 관리자 계정 상세 GET·role 변경·비밀번호 변경·삭제를 목록 화면에서 임의 구현하지 않는다.

## 3. 운영 레이아웃

좌측 또는 상단에 운영 영역을 구분하고 중앙에 필터·목록·상세를 배치한다. 좁은 화면에서는 필터와 목록을 사용 가능한 순서로 재배치한다. 표를 사용하면 긴 제목·이름·ID가 잘리더라도 원문을 확인할 수 있어야 한다.

현재 관리자 역할, 로그인 상태, 로그아웃 진입점을 표시한다. 사용자 블로그의 개인 Board·작성 기능·Naver 로그인 메뉴는 넣지 않는다. 공개 콘텐츠를 확인하는 링크는 사용자 web의 주소로 연결한다.

## 4. 목록과 필터

서버가 허용하는 필터·정렬·페이지를 사용하고 URL에 저장한다. 계정 목록처럼 필터를 지원하지 않는 API에는 임의 q를 보내지 않는다. 검색 요청 경합과 뒤로 가기 상태를 처리한다.

콘텐츠 목록은 visibilityStatus·isBlocked와 소유자 식별 조건 등 API가 지원하는 항목만 사용한다. 댓글 운영 목록은 부모 ID·authorId·visibility·deleted의 실제 지원 범위를 따른다. 회원 검색은 서버 검색 대상과 일치시킨다.

전체 상태를 조회하는 운영 목록과 공개 사용자 목록을 혼합하지 않는다. 공개 여부, 관리자 차단, 논리 삭제, 소유자 상태는 서로 다른 축으로 표시한다. 현재 페이지 건수를 전체 운영 통계로 표시하지 않는다.

## 5. 대상별 동작

### 콘텐츠

검수 상세에서는 안전한 API 응답만 표시한다. 차단은 작성자의 PUBLIC/HIDDEN과 독립적이다. 삭제된 콘텐츠에 차단 상태를 변경하면 409일 수 있고 삭제 복원은 제공하지 않는다. 관리자용 미디어 접근도 서버가 발급한 URL과 권한을 따른다.

차단·해제·강제 삭제 전에 대상과 결과를 구체적으로 안내한다. 일괄 처리 API가 없으면 일괄 처리 성공을 약속하지 않는다. 강제 삭제 이후 목록과 상세를 갱신하고 작성자·연결 관계 변경까지 서버 결과를 기준으로 표시한다.

### 댓글

SECRET과 삭제 메타데이터는 운영 권한으로 조회할 수 있다. 삭제 본문을 표시하거나 복구하지 않는다. 작성·수정 기능과 실제 제공되지 않는 관리자 단일 상세 화면을 만들지 않는다.

### 회원

상태 변경은 ACTIVE/SUSPENDED 전환과 서버가 허용하는 대상만 사용한다. WITHDRAWAL_PENDING을 일반 상태 변경 UI로 취소하지 않는다. OAuth 식별자·인증 비밀·미제공 개인정보를 표시하지 않는다.

### Category·Tag

생성·부분 수정·비활성화를 제공한다. Category 최대 깊이·최하위 참조·계층 충돌·slug 중복은 API 오류로 안내한다. 비활성화가 기존 콘텐츠 참조를 삭제하는 것으로 설명하지 않는다.

### 관리자 계정

MASTER에게만 메뉴를 제공하고 서버도 역할을 검증한다. 생성 역할은 MANAGER다. MASTER·본인·없는 계정의 상태 변경 제한을 서버대로 처리한다. 비활성화 및 같은 상태 설정도 기존 세션 폐기에 영향을 줄 수 있으며 활성화가 이전 세션을 복구하지 않는다는 점을 안내한다.

## 6. 공통 화면 상태와 검증

세션 복구 중·로그인 실패·만료·권한 거부·빈 목록·검색 없음·네트워크 오류·충돌·저장 중·완료 상태를 제공한다. 위험 동작의 중복 제출을 막고 실패 후 자동 재전송하지 않는다.

MANAGER가 MASTER 전용 화면 URL을 직접 입력한 경우, 관리자 비활성화·sid 폐기, 쿠키 Origin 오류, 차단·해제·삭제의 충돌, SECRET·삭제 댓글, 존재하지 않는 대상, 목록 필터·정렬의 범위, 모바일·키보드 동작을 검증한다. 실제 API 응답과 화면 시안을 함께 대조한 뒤 완료를 기록한다.
