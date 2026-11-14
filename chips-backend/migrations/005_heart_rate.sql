CREATE TABLE IF NOT EXISTS heart_rate_archives (
    owner_id VARCHAR(36) NOT NULL,
    id VARCHAR(36) NOT NULL,
    device_id VARCHAR(36) NOT NULL REFERENCES devices(id),
    manifest TEXT,
    complete BOOLEAN NOT NULL DEFAULT FALSE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (owner_id, id)
);
CREATE TABLE IF NOT EXISTS heart_rate_chunks (
    owner_id VARCHAR(36) NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    kind VARCHAR(8) NOT NULL CHECK (kind IN ('runs','events')),
    start_index INTEGER NOT NULL CHECK (start_index >= 0),
    payload TEXT NOT NULL,
    items INTEGER NOT NULL CHECK (items BETWEEN 1 AND 128),
    samples INTEGER NOT NULL CHECK (samples >= 0),
    PRIMARY KEY (owner_id, session_id, kind, start_index),
    FOREIGN KEY (owner_id, session_id) REFERENCES heart_rate_archives(owner_id,id) ON DELETE CASCADE
);
ALTER TABLE activities ADD COLUMN IF NOT EXISTS hr_session_id VARCHAR(36);
CREATE UNIQUE INDEX IF NOT EXISTS activities_hr_session ON activities(owner_id, hr_session_id);
INSERT INTO schema_migrations(version) VALUES (5) ON CONFLICT DO NOTHING;
