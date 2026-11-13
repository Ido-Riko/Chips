-- Owner-scoped integration snapshots and public map metadata. No existing data changes.
CREATE TABLE IF NOT EXISTS beat_saber_connections (
    owner_id VARCHAR(36) PRIMARY KEY,
    player_id VARCHAR(25) NOT NULL,
    generation VARCHAR(36) NOT NULL,
    updated_at BIGINT NOT NULL DEFAULT 0,
    snapshot TEXT
);
CREATE TABLE IF NOT EXISTS beat_saver_cache (
    hash VARCHAR(40) PRIMARY KEY,
    fetched_at BIGINT NOT NULL,
    payload TEXT NOT NULL
);
INSERT INTO schema_migrations(version) VALUES (2) ON CONFLICT DO NOTHING;
