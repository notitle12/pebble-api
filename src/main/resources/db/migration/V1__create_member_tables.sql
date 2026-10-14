CREATE TABLE member (
    id BIGINT PRIMARY KEY,
    nickname VARCHAR(30) NOT NULL,
    profile_image_url VARCHAR(2048),
    status VARCHAR(20) NOT NULL,
    withdrawal_requested_at TIMESTAMPTZ,
    withdrawal_scheduled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_member_status
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'WITHDRAWAL_PENDING')),
    CONSTRAINT ck_member_withdrawal_timestamps
        CHECK (
            (status = 'WITHDRAWAL_PENDING'
                AND withdrawal_requested_at IS NOT NULL
                AND withdrawal_scheduled_at IS NOT NULL)
            OR
            (status <> 'WITHDRAWAL_PENDING'
                AND withdrawal_requested_at IS NULL
                AND withdrawal_scheduled_at IS NULL)
        )
);

CREATE TABLE member_oauth_identity (
    id BIGINT PRIMARY KEY,
    member_id BIGINT NOT NULL,
    provider VARCHAR(20) NOT NULL,
    provider_subject VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_member_oauth_identity_member
        FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE RESTRICT,
    CONSTRAINT ck_member_oauth_identity_provider
        CHECK (provider IN ('NAVER')),
    CONSTRAINT uk_member_oauth_identity_provider_subject
        UNIQUE (provider, provider_subject),
    CONSTRAINT uk_member_oauth_identity_member_provider
        UNIQUE (member_id, provider)
);
