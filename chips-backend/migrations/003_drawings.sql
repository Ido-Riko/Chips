-- Keep images with their owner-scoped metadata and feedback in database backups.
CREATE TABLE IF NOT EXISTS drawings (
    owner_id VARCHAR(36) NOT NULL,
    id VARCHAR(36) NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    image BYTEA,
    goal TEXT NOT NULL DEFAULT '',
    created_at BIGINT NOT NULL,
    feedback TEXT,
    analyzed_at BIGINT,
    model VARCHAR(200),
    deleted BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (owner_id, id)
);
INSERT INTO schema_migrations(version) VALUES (3) ON CONFLICT DO NOTHING;
