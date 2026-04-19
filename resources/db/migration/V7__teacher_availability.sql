-- Teacher availability: weekly recurring schedule + one-off exceptions
-- Plus teacher-configurable booking guardrails on the users table.

ALTER TABLE users ADD COLUMN timezone VARCHAR(64) NOT NULL DEFAULT 'Europe/Berlin';
-- Nullable: these only apply to TEACHER role. Service layer defaults to
-- (buffer=0, notice=0) when null so students/admins never read meaningful
-- values for themselves.
ALTER TABLE users ADD COLUMN booking_buffer_minutes INT;
ALTER TABLE users ADD COLUMN booking_min_notice_hours INT;

CREATE TYPE AVAILABILITY_EXCEPTION_TYPE AS ENUM ('BLOCKED', 'AVAILABLE');

CREATE TABLE teacher_weekly_availability
(
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    teacher_id  UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    day_of_week SMALLINT    NOT NULL,
    start_time  TIME        NOT NULL,
    end_time    TIME        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_tw_day CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT chk_tw_order CHECK (start_time < end_time)
);

CREATE INDEX idx_tw_teacher_day ON teacher_weekly_availability (teacher_id, day_of_week);

CREATE TABLE teacher_availability_exception
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    teacher_id UUID                        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type       AVAILABILITY_EXCEPTION_TYPE NOT NULL,
    start_at   TIMESTAMPTZ                 NOT NULL,
    end_at     TIMESTAMPTZ                 NOT NULL,
    reason     TEXT,
    created_at TIMESTAMPTZ                 NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ                 NOT NULL DEFAULT now(),
    CONSTRAINT chk_tae_order CHECK (start_at < end_at)
);

CREATE INDEX idx_tae_teacher_window ON teacher_availability_exception (teacher_id, start_at, end_at);
