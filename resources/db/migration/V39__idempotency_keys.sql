-- Idempotency-Key on create endpoints: one row per (user, key) for 24h. response_status is null while the first
-- request runs; afterwards a retry with the same key and payload gets response_status/response_body replayed.
CREATE TABLE idempotency_keys
(
    user_id         UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    idempotency_key VARCHAR(255) NOT NULL,
    method          VARCHAR(10)  NOT NULL,
    path            VARCHAR(500) NOT NULL,
    -- SHA-256 (hex) of the request body as the server parsed it.
    request_hash    CHAR(64)     NOT NULL,
    response_status INTEGER,
    response_body   TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (user_id, idempotency_key)
);

CREATE INDEX idx_idempotency_keys_expires_at ON idempotency_keys (expires_at);
