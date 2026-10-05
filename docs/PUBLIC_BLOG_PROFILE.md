# 공개 블로그 프로필 계약

GET `/api/v1/blogs/{handle}`는 Guest가 공개 글 유무와 무관하게 블로그의 표시 정보를 조회하는 경로다. 응답은 `data: {id, handle, nickname, blogName, profileImageUrl}`이며 ID는 BIGINT 문자열, 이미지 URL은 문자열 또는 null이다. 회원 상태·이메일·OAuth 식별자·변경 가능 시각은 노출하지 않는다.

소문자 영문으로 시작하고 영문·숫자·하이픈으로 구성된 3~30자 handle을 받으며 마지막 문자는 영문 또는 숫자다. 없는 블로그, 프로필 미완료, 탈퇴 대기 회원은 404다. 정지 회원의 기존 공개 블로그는 유지한다. query와 GET 본문은 400이며 정확한 GET 경로에만 공개 접근과 허용 Origin CORS를 적용한다. 인증·CSRF 정책은 그대로 사용한다.

프론트는 이 API의 id로 공개 Board 트리를 조회한다. 글의 첫 작성자로 프로필을 추정하지 않으므로 빈 블로그·빈 폴더·목록 끝 페이지에도 같은 블로그 정보를 표시한다.

검증: PublicBlogIntegrationTest 5건과 기존 회원 프로필 11건·Post 23건·Board 9건 통합 테스트 통과. 프론트 전체 91건과 프로덕션 빌드 통과. 로컬 실제 API에서 기존 local-preview 빈 폴더와 공개 글이 없는 empty-blog-preview를 확인했다.

로컬 검증용 데이터: member 910000000000009100(handle empty-blog-preview), Board 910000000000009101. OAuth identity나 인증 정보가 없는 표시 전용 프로필이며 기존 콘텐츠를 수정하지 않았다. 재확인을 위해 삭제하지 않고 남긴다.
