CREATE TABLE IF NOT EXISTS pantry_transactions (
    owner_id VARCHAR(36) NOT NULL,
    id VARCHAR(36) NOT NULL,
    created_at BIGINT NOT NULL,
    payload TEXT NOT NULL,
    PRIMARY KEY (owner_id, id)
);
INSERT INTO schema_migrations(version) VALUES (4) ON CONFLICT DO NOTHING;
