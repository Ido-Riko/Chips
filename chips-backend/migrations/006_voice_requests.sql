-- Permanent deduplication identities. Never delete keys to retry inference.
CREATE TABLE IF NOT EXISTS voice_requests (
    owner_id VARCHAR(36) NOT NULL,
    id VARCHAR(36) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL,
    transcript TEXT,
    answer TEXT,
    error_code VARCHAR(64),
    status_code INTEGER,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (owner_id, id)
);
INSERT INTO schema_migrations(version) VALUES (6) ON CONFLICT DO NOTHING;
