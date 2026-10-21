-- Additive PostgreSQL migration. Run once through the migrate command, never on startup.
CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY);
CREATE TABLE IF NOT EXISTS devices (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    revoked BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS ix_devices_owner_id ON devices(owner_id);
CREATE TABLE IF NOT EXISTS activities (
    owner_id VARCHAR(36) NOT NULL,
    id VARCHAR(36) NOT NULL,
    device_id VARCHAR(36) NOT NULL REFERENCES devices(id),
    type VARCHAR(16) NOT NULL CHECK (type IN ('WORKOUT','BEAT_SABER','DRAWING','JAPANESE')),
    occurred_at BIGINT NOT NULL,
    zone_id VARCHAR(128) NOT NULL,
    local_day VARCHAR(10) NOT NULL,
    note TEXT NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    deleted BOOLEAN NOT NULL,
    PRIMARY KEY (owner_id, id)
);
CREATE TABLE IF NOT EXISTS voice_logs (
    id SERIAL PRIMARY KEY,
    received_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    transcription TEXT, response TEXT, confidence DOUBLE PRECISION
);
ALTER TABLE voice_logs ADD COLUMN IF NOT EXISTS owner_id VARCHAR(36);
CREATE INDEX IF NOT EXISTS ix_voice_logs_owner_id ON voice_logs(owner_id);
INSERT INTO schema_migrations(version) VALUES (1) ON CONFLICT DO NOTHING;
