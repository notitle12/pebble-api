CREATE TABLE member_agreement (
    id BIGINT PRIMARY KEY,
    member_id BIGINT NOT NULL REFERENCES member(id) ON DELETE CASCADE,
    terms_version VARCHAR(32) NOT NULL,
    privacy_version VARCHAR(32) NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL,
    age_confirmed BOOLEAN NOT NULL CHECK (age_confirmed),
    CONSTRAINT uk_member_agreement_versions UNIQUE (member_id, terms_version, privacy_version)
);
-- 기존 회원의 동의는 추정하거나 소급 생성하지 않는다.
