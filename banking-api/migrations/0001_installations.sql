CREATE TABLE IF NOT EXISTS installations (
    installation_hash TEXT PRIMARY KEY NOT NULL,
    provider_user_id TEXT UNIQUE NOT NULL,
    created_at TEXT NOT NULL,
    last_seen_at TEXT NOT NULL,
    provider_user_ready INTEGER NOT NULL DEFAULT 0 CHECK (provider_user_ready IN (0, 1))
);

CREATE INDEX IF NOT EXISTS installations_last_seen_at_idx ON installations(last_seen_at);
