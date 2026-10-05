# Pebble 프론트엔드 인증과 보안

작성일: 2026-10-03. [백엔드 SECURITY](../backend/contracts/SECURITY.md)와 [API](../backend/contracts/API.md)의 정책을 클라이언트에서 준수하는 기준이다. 인증 방식을 새로 정하지 않는다.

## 1. 인증과 권한의 소유권

인증은 누구인지 확인하는 절차, 인가는 그 사람이 해당 일을 할 수 있는지 판단하는 절차다. 화면은 메뉴와 안내를 구성하고 서버는 역할·계정 상태·소유권·리소스 상태를 검증한다. 프론트가 보낸 role이나 ID를 권한 근거로 만들지 않는다.

USER와 관리자 로그인·토큰·refresh·logout 경로를 분리한다. 관리자 세션을 일반 회원 쓰기로 사용하지 않는다. USER의 계정 상태와 관리자의 ACTIVE/INACTIVE 상태를 서로 같은 enum으로 합치지 않는다.

## 2. 자격 증명 보관

Access JWT는 로그인·갱신 응답에서 받아 메모리에만 보관하고 Authorization: Bearer로 전달한다. 새로고침하면 메모리가 사라지므로 refresh cookie를 사용해 필요한 시점에 세션 복구를 시도한다.

Refresh Token은 서버가 HttpOnly·Secure·SameSite=Lax cookie로 설정하며 JavaScript가 읽거나 본문으로 보내지 않는다. localStorage·sessionStorage·URL·로그·분석 이벤트에 자격 증명을 남기지 않는다. JWT의 내용을 디코딩해 UI 힌트를 얻더라도 서버 권한 검증을 대신하지 않는다.

## 3. Naver 로그인

아래 API 경로는 /api/v1 기준이다.

1. 쿠키를 포함해 POST /auth/naver/authorization을 호출한다.
2. 서버가 반환한 authorizationUrl로 이동한다.
3. 등록된 callback에서 code와 state를 읽는다.
4. 쿠키를 포함해 POST /auth/naver/login에 authorizationCode·state를 전송한다.
5. 성공하면 Access Token을 메모리에 보관하고 응답 회원 상태·profileCompleted에 따라 화면을 안내한다.

callback 경로, 서버 NAVER_REDIRECT_URI, Naver 등록 URI는 같아야 한다. 아직 최종 callback 라우트는 미확정이다. React Effect의 중복 실행·새로고침으로 일회용 code를 재사용하지 않도록 조정한다. 민감 query는 처리에 필요한 값을 캡처한 뒤 주소에서 제거하고 자동 재시도하지 않는다. 로그인 후 이동 주소는 허용된 내부 경로만 사용해 외부 리다이렉트를 막는다.

탈퇴 취소는 로그인 성공과 다르다. 취소 성공 뒤 새 토큰이 발급되지 않으므로 재로그인을 안내한다.

## 4. refresh와 세션 복구

한 탭에서는 진행 중 refresh 요청을 공유한다. 여러 API의 401마다 별도 refresh를 동시에 호출하지 않는다. auth endpoint 자신의 오류로 refresh 재귀 루프를 만들지 않는다. 로그인·refresh·logout·계정 전환 순서를 조정해 늦게 도착한 refresh 응답이 로그아웃 후 토큰을 다시 복구하지 않도록 세션 세대 등을 확인한다. 메모리 응답을 무시해도 브라우저의 Set-Cookie 처리는 취소되지 않는다. 진행 중 refresh와 logout 요청을 직렬화하고, logout 실패·응답 유실은 서버 세션 폐기 완료와 구분해 표시한다.

같은 브라우저의 여러 탭은 HttpOnly cookie를 공유하므로 탭 간 회전 경합도 처리해야 한다. 동일 Origin의 탭끼리 사용할 수 있는 잠금·이벤트 알림 방식을 검토하고 실제 브라우저 지원·실패 경계를 확인한다. 서로 다른 Origin은 브라우저 조정 수단을 자동 공유하지 않으므로 같은 USER 세션을 여러 frontend Origin에서 사용하는 경우 별도 설계가 필요하다. 메모리 Promise 하나만으로 여러 탭의 문제를 해결했다고 기록하지 않는다. 토큰을 브라우저 영구 저장소로 공유하지 않는다.

사용한 refresh token의 재전송은 재사용 탐지로 Family를 폐기할 수 있다. 응답 유실·타임아웃 뒤 이전 요청을 자동 재시도하지 않는다. 자격을 복구하지 못하면 재로그인 경로를 제공한다. 비활성 세션을 유지하기 위해 주기적인 background refresh를 보내지 않는다.

현재 Access JWT는 900초다. 관리자 세션의 서버 sid 검사와 USER 오프라인 검증 정책은 다르다. 클라이언트가 임의로 토큰 만료나 서버의 폐기 정책을 변경하지 않는다.

## 5. SSR과 개인정보

서버 렌더링은 브라우저 메모리의 토큰을 직접 사용할 수 없다. 초기에는 공개 데이터를 미인증 조회하고, 개인 정보·SECRET 댓글·소유자 HIDDEN 콘텐츠는 인증 복구 뒤 브라우저에서 조회하는 방향을 제안한다.

이를 위해 Access Token을 별도 cookie로 옮기거나 서버로 영구 보관하는 BFF를 조용히 추가하지 않는다. BFF 도입은 API·쿠키·CORS·CSRF·서버 캐시 계약의 변경이며 별도 판단이 필요하다. 개인·관리자 API 응답과 HTML을 공용 캐시에 넣지 않는다.

## 6. 도메인과 CORS

실제 도메인의 web·admin·api hostname을 같은 HTTPS 사이트 아래 구성하는 방향이다. 서로 다른 hostname은 다른 Origin이므로 각각 필요한 Origin·Method·Header를 정확히 허용한다. 쿠키 요청은 credentials: include를 사용하고 와일드카드 Origin을 사용하지 않는다.

쿠키 경로·Domain·SameSite와 서버의 필수 Origin 검사를 유지한다. preview 도메인과 production API는 다른 사이트일 수 있으며 같은 로그인 조건이 성립한다고 가정하지 않는다. 운영 Origin을 넓혀 preview를 편하게 만드는 방식은 피한다.

## 7. 콘텐츠·링크·이미지

사용자 콘텐츠는 기본 React escaping을 사용한다. HTML 직접 삽입·Markdown의 raw HTML·외부 embed는 필요한 경우 허용 목록과 검증을 설계한다. 현재 TEXT/CODE 블록이 있다는 이유로 HTML 실행을 허용하지 않는다. 외부 링크는 위험한 scheme을 거부하고 새 창 연결 정책을 확인한다.

R2 signed URL은 유효 기간 동안 접근 가능한 자격으로 취급하고 로그·분석 이벤트에 넣지 않는다. 이미지 최적화 프록시나 CDN이 비공개 이미지를 만료보다 오래 제공하는지 확인한다. 초기에는 API가 제공한 이미지 URL을 직접 사용하는 방향을 검토하며, 장기 공개 이미지 캐시를 기본 도입하지 않는다.

## 8. 필수 검증

정상 로그인·취소·state 오류·중복 callback, 새로고침 복구, 동시 401·다중 탭 refresh, refresh 응답 유실, logout과 refresh 경합, 정지·탈퇴·관리자 비활성, 계정 전환 뒤 댓글 캐시, 허용·불허 Origin, 비공개 HTML·미디어 캐시를 확인한다. 모의 테스트 통과와 실제 OAuth·쿠키 검증을 분리한다.
