-- 이름 등록만 확장한다. 실제 로그인은 등록된 공급자 클라이언트와 기존 HTTP 허용 경로로 제한한다.
ALTER TABLE member_oauth_identity DROP CONSTRAINT ck_member_oauth_identity_provider;
ALTER TABLE member_oauth_identity ADD CONSTRAINT ck_member_oauth_identity_provider
    CHECK (provider IN ('NAVER', 'KAKAO', 'GOOGLE'));
