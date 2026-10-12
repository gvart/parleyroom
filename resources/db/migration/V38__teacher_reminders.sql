-- Stage 3 teacher reminders: per-teacher settings (a missing row means the defaults), a claim log that keeps
-- each in-app reminder to one notification however many pods run the job, and the new notification types.

CREATE TABLE teacher_reminder_settings
(
    teacher_id             UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    after_lesson_enabled   BOOLEAN     NOT NULL DEFAULT TRUE,
    after_lesson_hours     INTEGER     NOT NULL DEFAULT 2 CHECK (after_lesson_hours BETWEEN 0 AND 48),
    after_lesson_email     BOOLEAN     NOT NULL DEFAULT FALSE,
    after_lesson_telegram  BOOLEAN     NOT NULL DEFAULT TRUE,
    before_lesson_enabled  BOOLEAN     NOT NULL DEFAULT TRUE,
    -- DAY_BEFORE: at before_lesson_time on the day before (teacher's zone); HOURS_BEFORE: before_lesson_hours ahead.
    before_lesson_mode     VARCHAR(16) NOT NULL DEFAULT 'DAY_BEFORE' CHECK (before_lesson_mode IN ('DAY_BEFORE', 'HOURS_BEFORE')),
    before_lesson_time     TIME        NOT NULL DEFAULT '18:00',
    before_lesson_hours    INTEGER     NOT NULL DEFAULT 3 CHECK (before_lesson_hours BETWEEN 1 AND 72),
    before_lesson_email    BOOLEAN     NOT NULL DEFAULT FALSE,
    before_lesson_telegram BOOLEAN     NOT NULL DEFAULT TRUE,
    digest_enabled         BOOLEAN     NOT NULL DEFAULT TRUE,
    -- ISO day of week, 1 = Monday.
    digest_weekday         SMALLINT    NOT NULL DEFAULT 1 CHECK (digest_weekday BETWEEN 1 AND 7),
    digest_time            TIME        NOT NULL DEFAULT '08:00',
    digest_email           BOOLEAN     NOT NULL DEFAULT FALSE,
    digest_telegram        BOOLEAN     NOT NULL DEFAULT TRUE,
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- In-app reminders sent; email and Telegram claim theirs in email_log / telegram_log.
-- ref_id: the lesson (after/before) or the teacher (digest); variant: the lesson start or the ISO week.
CREATE TABLE teacher_reminder_log
(
    id         UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    teacher_id UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind       VARCHAR(32) NOT NULL,
    ref_id     UUID        NOT NULL,
    variant    VARCHAR(32) NOT NULL DEFAULT '',
    sent_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_teacher_reminder_log UNIQUE (kind, ref_id, teacher_id, variant)
);

ALTER TYPE NOTIFICATION_TYPE ADD VALUE 'LESSON_FOLLOW_UP_DUE';
ALTER TYPE NOTIFICATION_TYPE ADD VALUE 'LESSON_PREP_DUE';
ALTER TYPE NOTIFICATION_TYPE ADD VALUE 'WEEKLY_DIGEST';

-- What a notification is about beyond its reference, e.g. ["WRAP_UP", "HOMEWORK"] for LESSON_FOLLOW_UP_DUE.
ALTER TABLE notifications ADD COLUMN details TEXT[];
