-- Telegram reminders: per-user category toggles, a flag set when the bot may not message the user
-- (Telegram answered 403), and a send log that reminder jobs claim like email_log.

ALTER TABLE users
    ADD COLUMN telegram_lesson_reminders   BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN telegram_homework_reminders BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN telegram_unreachable_at     TIMESTAMPTZ;

CREATE TABLE telegram_log
(
    id                UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    recipient_user_id UUID REFERENCES users (id) ON DELETE CASCADE,
    chat_id           BIGINT      NOT NULL,
    kind              VARCHAR(64) NOT NULL,
    ref_id            UUID,
    variant           VARCHAR(32),
    sent_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    success           BOOLEAN     NOT NULL
);

-- One reminder per (kind, lesson/homework, recipient, variant); NULLs compare equal.
-- Test messages have no ref_id and are not deduplicated.
CREATE UNIQUE INDEX uq_telegram_log_dedupe ON telegram_log (kind, ref_id, recipient_user_id, variant) NULLS NOT DISTINCT
    WHERE ref_id IS NOT NULL;

CREATE INDEX idx_telegram_log_sent_at ON telegram_log (sent_at);
