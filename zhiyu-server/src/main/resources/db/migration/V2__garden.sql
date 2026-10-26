CREATE TABLE garden_spaces (
    token_hash VARCHAR(64) PRIMARY KEY,
    state_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
