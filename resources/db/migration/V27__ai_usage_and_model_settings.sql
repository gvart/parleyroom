-- Which Claude model serves each AI use case (admin-editable), and a ledger of every model call.

CREATE TABLE ai_model_settings
(
    use_case   VARCHAR(32) PRIMARY KEY,
    model      VARCHAR(128) NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (id) ON DELETE SET NULL
);

INSERT INTO ai_model_settings (use_case, model)
VALUES ('GENERATE', 'claude-haiku-5-5'),
       ('REFINE', 'claude-haiku-5-5'),
       ('FILL_TRANSLATIONS', 'claude-haiku-5-5'),
       ('SUGGEST_TAGS', 'claude-haiku-5-5'),
       ('SENTENCE_FEEDBACK', 'claude-haiku-5-5');

-- One row per provider call: a validation retry is a second row, a failed call has zero tokens.
-- cost_usd is fixed at insert from the configured prices (ai.pricing).
CREATE TABLE ai_usage
(
    id                 UUID PRIMARY KEY        DEFAULT gen_random_uuid(),
    user_id            UUID           NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    use_case           VARCHAR(32)    NOT NULL,
    model              VARCHAR(128)   NOT NULL,
    job_id             UUID REFERENCES generation_jobs (id) ON DELETE SET NULL,
    success            BOOLEAN        NOT NULL,
    error_code         VARCHAR(64),
    input_tokens       INTEGER        NOT NULL DEFAULT 0,
    output_tokens      INTEGER        NOT NULL DEFAULT 0,
    cache_read_tokens  INTEGER        NOT NULL DEFAULT 0,
    cache_write_tokens INTEGER        NOT NULL DEFAULT 0,
    cost_usd           NUMERIC(12, 6) NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_ai_usage_created ON ai_usage (created_at);
CREATE INDEX idx_ai_usage_user_created ON ai_usage (user_id, created_at);
