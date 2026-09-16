CREATE TABLE IF NOT EXISTS sync_tasks (
    installation_hash TEXT NOT NULL,
    connection_id TEXT NOT NULL,
    task_id TEXT NOT NULL,
    web_form_id TEXT,
    web_form_url TEXT,
    created_at TEXT NOT NULL,
    PRIMARY KEY (installation_hash, connection_id)
);

CREATE TABLE IF NOT EXISTS request_limits (
    bucket_key TEXT PRIMARY KEY NOT NULL,
    window_id INTEGER NOT NULL,
    hits INTEGER NOT NULL CHECK (hits > 0)
);
