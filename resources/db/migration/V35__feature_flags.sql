-- Server-side feature flags, keyed by the flag's API name (e.g. 'roadmap').
-- A missing row means the flag is off, so a new flag needs no migration.
CREATE TABLE feature_flags
(
    name       TEXT PRIMARY KEY,
    enabled    BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (id) ON DELETE SET NULL
);
