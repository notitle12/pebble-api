CREATE TABLE category (
    id BIGINT PRIMARY KEY,
    parent_id BIGINT,
    name VARCHAR(50) NOT NULL,
    slug VARCHAR(100) NOT NULL,
    display_order INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_category_parent FOREIGN KEY (parent_id) REFERENCES category (id) ON DELETE RESTRICT,
    CONSTRAINT uk_category_slug UNIQUE (slug),
    CONSTRAINT ck_category_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE INDEX idx_category_parent_order ON category (parent_id, display_order);

CREATE TABLE tag (
    id BIGINT PRIMARY KEY,
    name VARCHAR(50) NOT NULL,
    slug VARCHAR(100) NOT NULL,
    display_order INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_tag_slug UNIQUE (slug),
    CONSTRAINT ck_tag_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);
