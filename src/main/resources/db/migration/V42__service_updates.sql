CREATE TABLE service_update (
    id BIGINT PRIMARY KEY,
    title VARCHAR(160) NOT NULL CHECK (char_length(title) BETWEEN 1 AND 160),
    summary VARCHAR(300) NOT NULL,
    body TEXT NOT NULL CHECK (char_length(body) BETWEEN 1 AND 20000),
    type VARCHAR(20) NOT NULL CHECK (type IN ('FEATURE','PERFORMANCE','FIX')),
    version VARCHAR(40),
    status VARCHAR(20) NOT NULL CHECK (status IN ('DRAFT','PUBLISHED')),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    published_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_service_update_publication CHECK (status <> 'PUBLISHED' OR published_at IS NOT NULL)
);
CREATE INDEX idx_service_update_public ON service_update(published_at DESC,id DESC) WHERE deleted_at IS NULL AND status='PUBLISHED';
CREATE INDEX idx_service_update_management ON service_update(created_at DESC,id DESC) WHERE deleted_at IS NULL;
