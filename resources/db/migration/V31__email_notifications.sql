-- Email notifications: per-user reminder opt-outs, platform settings, and a send log
-- that reminder jobs claim (insert-ignore) so each reminder goes out exactly once.

ALTER TABLE users
    ADD COLUMN email_lesson_reminders   BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN email_homework_reminders BOOLEAN NOT NULL DEFAULT TRUE;

-- Single row (id = 1) of admin-editable platform settings.
CREATE TABLE app_settings
(
    id                 SMALLINT PRIMARY KEY     DEFAULT 1 CHECK (id = 1),
    invite_expiry_days INTEGER     NOT NULL DEFAULT 7 CHECK (invite_expiry_days BETWEEN 1 AND 90),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by         UUID REFERENCES users (id) ON DELETE SET NULL
);

INSERT INTO app_settings (id) VALUES (1);

CREATE TABLE email_log
(
    id                UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    recipient_user_id UUID REFERENCES users (id) ON DELETE CASCADE,
    recipient_email   VARCHAR(255) NOT NULL,
    kind              VARCHAR(64)  NOT NULL,
    ref_id            UUID,
    variant           VARCHAR(32),
    sent_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    success           BOOLEAN      NOT NULL
);

-- One reminder per (kind, lesson/assignment, recipient, variant); NULLs compare equal.
CREATE UNIQUE INDEX uq_email_log_dedupe ON email_log (kind, ref_id, recipient_user_id, variant) NULLS NOT DISTINCT
    WHERE ref_id IS NOT NULL;

CREATE INDEX idx_email_log_sent_at ON email_log (sent_at);
