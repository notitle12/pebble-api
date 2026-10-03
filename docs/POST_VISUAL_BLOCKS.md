# 게시글 표현 블록 확장

API Issue #60 / Web Issue #14 · 2026-10-03

## 단계와 사용자 입력

| 단계 | 입력 | 표시 | 상태 |
|---|---|---|---|
| 1. TABLE | 테이블명, 컬럼·타입·PK·NULL 허용·FK 설명 | 의미 있는 HTML 명세 표 | 이번 구현 |
| 2. ARCHITECTURE | 정해진 서비스·클라우드·그룹과 연결 설명 | 자동 배치하는 구조도 | 후속 설계 |
| 3. ERD | 테이블과 명시적인 관계·카디널리티 | 테이블 간 관계도 | 후속 설계 |

아키텍처 편집기는 Oracle Cloud/AWS/Cloudflare 그룹, Docker 그룹, 앱 서버, DB, 캐시, 스토리지와 연결 설명을 선택하는 방향이다. 최초에는 자동 배치와 제한된 입력을 우선하며 자유 도형·임의 HTML·외부 script는 요구하지 않는다. Mermaid 등 도식 렌더러 도입은 실제 구조도 단계에서 dependency·표시·접근성·콘텐츠 검증을 검토한다. 현재 API의 블록 타입에는 ARCHITECTURE/ERD가 없다.

## TABLE 계약

기존 Post POST/PATCH blocks에 다음 객체를 넣는다. `content`는 객체가 아니라 JSON 문자열이다.

```json
{
  "type": "TABLE",
  "content": "{\"schemaVersion\":1,\"tableName\":\"member\",\"columns\":[{\"name\":\"id\",\"dataType\":\"BIGINT\",\"nullable\":false,\"primaryKey\":true}]}",
  "title": "회원 테이블",
  "language": null
}
```

입력 제한·오류는 API.md 6.3을 따른다. 직접 SQL을 실행하지 않으며 FK 문자열은 문서상의 참조 설명이다. PostgreSQL 실제 스키마와 일치하는지 자동 접속·검증하지 않는다. 중복 컬럼명·NULL 허용 PK·유효하지 않은 Unicode·미지 필드·중복 JSON 키·추가 문서·잘못된 버전을 거부한다. 복합 PK는 여러 컬럼에 primaryKey=true를 지정한다.

V14는 post_block.type CHECK를 확장하고 TABLE의 language=NULL을 보장한다. 기존 content TEXT 컬럼에 저장하며, 새 테이블/JSONB/응답 DTO는 만들지 않는다. 저장된 content를 Guest 조회에서도 그대로 반환하므로 web은 스키마를 검사한 뒤 안전한 텍스트 셀로 렌더링한다. 기존 TEXT/CODE·블록 정렬·회원 소유권·공개/숨김 규칙을 유지한다. 검색은 기존 content/title 부분 문자열 검색을 유지하므로 JSON 필드명도 검색 대상이 될 수 있다. 명세서 전용 검색은 이번 범위에 없다.

## 프론트 연결과 검증

web의 개발 전용 `/dev/table-spec`에서 입력과 실시간 미리보기를 확인할 수 있다. 생산 환경에서는 404를 반환한다. 현재 로그인·글 작성 화면은 아직 없어 일반 회원의 작성/게시 흐름이 완성되었다고 해석하지 않는다. 입력 컴포넌트와 TABLE 상세 표는 기존 Post 편집기 연결을 준비한 첫 단계다.

실제 로컬 검증에서는 기존 API 8080을 유지하고, 테스트 프로필·임시 서명 키·R2 비활성의 검증 API 8081을 실행했다. 전용 local-preview 회원의 테스트 글 integration-1에 실제 회원 PATCH로 TABLE을 추가하고 Guest GET으로 DB 저장 결과를 다시 조회했다. 프론트 3100은 8081에 연결해 TEXT/CODE/TABLE 혼합 상세를 확인했다. 임시 키·토큰은 Git·로그·문서에 남기지 않는다. 실제 OAuth 로그인 검증을 수행한 것은 아니다.

로컬 미리보기 콘텐츠는 API #58의 전용 데이터다. 개발용 seed/clean 도구는 해당 PR #59에 있으며 이번 API 기능 브랜치는 dev 기준으로 분기했다. TABLE 샘플은 fixture 글의 본문에만 추가해 기존 공개 개수·식별자를 유지한다.

## 실행 결과

- TABLE 입력 단위 테스트 5개와 기존 Post 통합 테스트(신규 저장/수정/조회/인가 1개 포함) 통과.
- 개발 DB 공유 상태의 전체 테스트는 fixture와 기존 초기 데이터 삭제/회원 개수 기대값이 충돌해 14개 실패했다. 임시 PostgreSQL DB와 전용 Redis로 격리해 전체 456개를 재실행했으며 실패·오류·스킵 0개로 통과했다. 임시 자원은 테스트 종료 후 정리했다.
- 실제 PATCH 저장 → DB → Guest GET → 프론트 혼합 상세 표 표시 확인. 모바일 390px에서 페이지 가로 넘침 없이 표 내부 680px 가로 스크롤 확인.
- web Node 22 테스트 22개·타입 검사 통과. 개발 입력에서 행 추가/삭제·중복 오류·PK 자동 NULL 금지·FK 표시·390px 가로 넘침 방지 확인. 최종 운영 빌드와 개발 경로 차단 확인은 web 인계 문서에 기록한다.
