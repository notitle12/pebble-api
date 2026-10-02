CREATE TABLE admin_account (
    id BIGINT PRIMARY KEY,
    login_id VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_admin_account_role CHECK (role IN ('MANAGER', 'MASTER')),
    CONSTRAINT ck_admin_account_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

ALTER TABLE post
    ADD CONSTRAINT fk_post_blocked_by_admin
        FOREIGN KEY (blocked_by_admin_id) REFERENCES admin_account (id) ON DELETE RESTRICT;

ALTER TABLE project
    ADD CONSTRAINT fk_project_blocked_by_admin
        FOREIGN KEY (blocked_by_admin_id) REFERENCES admin_account (id) ON DELETE RESTRICT;
