-- initial schema  -- V1__create_schema.sql
-- Sprachraum Database Schema

-- Enums
CREATE TYPE USER_ROLE AS ENUM ('ADMIN', 'STUDENT', 'TEACHER');
CREATE TYPE USER_STATUS AS ENUM ('ACTIVE', 'REQUEST', 'INACTIVE');
CREATE TYPE LANGUAGE_LEVEL AS ENUM ('A1', 'A2', 'B1', 'B2', 'C1', 'C2');
CREATE TYPE LESSON_TYPE AS ENUM ('ONE_ON_ONE', 'SPEAKING_CLUB', 'READING_CLUB');
CREATE TYPE LESSON_STATUS AS ENUM ('CONFIRMED', 'REQUEST', 'CANCELLED', 'COMPLETED', 'IN_PROGRESS');
CREATE TYPE HOMEWORK_CATEGORY AS ENUM ('WRITING', 'READING', 'GRAMMAR', 'VOCABULARY', 'LISTENING');
CREATE TYPE HOMEWORK_STATUS AS ENUM ('OPEN', 'SUBMITTED', 'IN_REVIEW', 'DONE', 'REJECTED');
CREATE TYPE VOCAB_CATEGORY AS ENUM ('NOUN', 'VERB', 'ADJECTIVE', 'ADVERB', 'GRAMMAR');
CREATE TYPE VOCAB_STATUS AS ENUM ('NEW', 'REVIEW', 'LEARNED');
CREATE TYPE GOAL_SET_BY AS ENUM ('TEACHER', 'STUDENT');
CREATE TYPE GOAL_STATUS AS ENUM ('ACTIVE', 'COMPLETED', 'ABANDONED');
CREATE TYPE MATERIAL_TYPE AS ENUM ('PDF', 'AUDIO', 'VIDEO', 'LINK');
CREATE TYPE ATTACHMENT_TYPE AS ENUM ('FILE', 'LINK');
CREATE TYPE LESSON_STUDENT_STATUS AS ENUM ('CONFIRMED', 'REQUESTED', 'REJECTED');
CREATE TYPE LESSON_EVENT_TYPE AS ENUM ('STATUS_CHANGE', 'RESCHEDULE_REQUESTED', 'RESCHEDULE_ACCEPTED', 'RESCHEDULE_REJECTED', 'LESSON_STARTED', 'LESSON_COMPLETED', 'LESSON_CANCELLED');

-- Users
CREATE TABLE users
(
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(255) NOT NULL UNIQUE,
    first_name    VARCHAR(255) NOT NULL,
    last_name     VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          USER_ROLE    NOT NULL,
    avatar_url    TEXT,
    initials      VARCHAR(4)   NOT NULL,
    level         LANGUAGE_LEVEL,
    points        INT              DEFAULT 0,
    status        USER_STATUS      DEFAULT 'ACTIVE',
    locale        VARCHAR(5)       DEFAULT 'en',
    created_at    TIMESTAMPTZ      DEFAULT now(),
    updated_at    TIMESTAMPTZ      DEFAULT now()
);

CREATE INDEX idx_users_role ON users (role);
CREATE INDEX idx_users_status ON users (status);

-- Teacher-Student relationships
CREATE TABLE teacher_students
(
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    teacher_id   UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    student_id   UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    lesson_types LESSON_TYPE[] NOT NULL DEFAULT '{}',
    status       USER_STATUS      DEFAULT 'REQUEST',
    started_at   TIMESTAMPTZ      DEFAULT now(),
    UNIQUE (teacher_id, student_id)
);

CREATE INDEX idx_teacher_students_teacher ON teacher_students (teacher_id);
CREATE INDEX idx_teacher_students_student ON teacher_students (student_id);

-- Lessons (also serves as calendar events)
CREATE TABLE lessons
(
    id               UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    title            VARCHAR(255) NOT NULL,
    type             LESSON_TYPE  NOT NULL,
    scheduled_at     TIMESTAMPTZ  NOT NULL,
    duration_minutes INT          NOT NULL DEFAULT 60,
    teacher_id       UUID         NOT NULL REFERENCES users (id),
    status           LESSON_STATUS         DEFAULT 'CONFIRMED',
    topic            VARCHAR(500) NOT NULL,
    level            LANGUAGE_LEVEL,
    max_participants INT,
    has_ai_summary   BOOLEAN               DEFAULT FALSE,
    started_at       TIMESTAMPTZ,
    ended_at         TIMESTAMPTZ,
    created_by       UUID         NOT NULL REFERENCES users (id),
    updated_by       UUID                  REFERENCES users (id),
    created_at       TIMESTAMPTZ           DEFAULT now(),
    updated_at       TIMESTAMPTZ           DEFAULT now()
);

CREATE INDEX idx_lessons_teacher ON lessons (teacher_id);
CREATE INDEX idx_lessons_scheduled ON lessons (scheduled_at);
CREATE INDEX idx_lessons_status ON lessons (status);
CREATE INDEX idx_lessons_type ON lessons (type);
CREATE INDEX idx_lessons_created_by ON lessons (created_by);

-- Lesson-Student join
CREATE TABLE lesson_students
(
    lesson_id  UUID NOT NULL REFERENCES lessons (id) ON DELETE CASCADE,
    student_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status     LESSON_STUDENT_STATUS DEFAULT 'CONFIRMED',
    attended   BOOLEAN DEFAULT FALSE,
    PRIMARY KEY (lesson_id, student_id)
);

CREATE INDEX idx_lesson_students_student_status ON lesson_students (student_id, status);

-- Lesson events (audit log for status changes and reschedules)
CREATE TABLE lesson_events
(
    id               UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    lesson_id        UUID        NOT NULL REFERENCES lessons (id) ON DELETE CASCADE,
    event_type       LESSON_EVENT_TYPE NOT NULL,
    actor_id         UUID        NOT NULL REFERENCES users (id),
    old_status       LESSON_STATUS,
    new_status       LESSON_STATUS,
    old_scheduled_at TIMESTAMPTZ,
    new_scheduled_at TIMESTAMPTZ,
    resolved         BOOLEAN     NOT NULL DEFAULT FALSE,
    note             TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_lesson_events_lesson ON lesson_events (lesson_id);
CREATE INDEX idx_lesson_events_actor ON lesson_events (actor_id);
CREATE INDEX idx_lesson_events_pending ON lesson_events (lesson_id, event_type, resolved)
    WHERE resolved = FALSE;

-- Lesson documents (1:1 with lesson)
CREATE TABLE lesson_documents
(
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lesson_id          UUID NOT NULL UNIQUE REFERENCES lessons (id) ON DELETE CASCADE,
    ai_summary         TEXT,
    teacher_notes      TEXT,
    teacher_went_well  TEXT,
    teacher_working_on TEXT,
    student_notes      TEXT,
    student_reflection TEXT,
    student_hard_today TEXT,
    created_at         TIMESTAMPTZ      DEFAULT now(),
    updated_at         TIMESTAMPTZ      DEFAULT now()
);

-- Corrections within a lesson document
CREATE TABLE lesson_corrections
(
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lesson_document_id UUID NOT NULL REFERENCES lesson_documents (id) ON DELETE CASCADE,
    incorrect          TEXT NOT NULL,
    correct            TEXT NOT NULL,
    order_index        INT              DEFAULT 0
);

CREATE INDEX idx_lesson_corrections_doc ON lesson_corrections (lesson_document_id);

-- New words introduced in a lesson
CREATE TABLE lesson_words
(
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lesson_document_id UUID         NOT NULL REFERENCES lesson_documents (id) ON DELETE CASCADE,
    german             VARCHAR(255) NOT NULL,
    english            VARCHAR(255) NOT NULL,
    order_index        INT              DEFAULT 0
);

CREATE INDEX idx_lesson_words_doc ON lesson_words (lesson_document_id);

-- Homework
CREATE TABLE homework
(
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lesson_id        UUID              REFERENCES lessons (id) ON DELETE SET NULL,
    student_id       UUID              NOT NULL REFERENCES users (id),
    teacher_id       UUID              NOT NULL REFERENCES users (id),
    title            VARCHAR(500)      NOT NULL,
    description      TEXT,
    category         HOMEWORK_CATEGORY NOT NULL,
    due_date         DATE,
    status           HOMEWORK_STATUS  DEFAULT 'OPEN',
    submission_text  TEXT,
    submission_url   TEXT,
    teacher_feedback TEXT,
    attachment_type  ATTACHMENT_TYPE,
    attachment_url   TEXT,
    attachment_name  VARCHAR(255),
    created_at       TIMESTAMPTZ      DEFAULT now(),
    updated_at       TIMESTAMPTZ      DEFAULT now()
);

CREATE INDEX idx_homework_student ON homework (student_id);
CREATE INDEX idx_homework_lesson ON homework (lesson_id);
CREATE INDEX idx_homework_status ON homework (status);
CREATE INDEX idx_homework_due ON homework (due_date);

-- Vocabulary words (student SRS)
CREATE TABLE vocabulary_words
(
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id          UUID           NOT NULL REFERENCES users (id),
    lesson_id           UUID           REFERENCES lessons (id) ON DELETE SET NULL,
    german              VARCHAR(255)   NOT NULL,
    english             VARCHAR(255)   NOT NULL,
    example_sentence    TEXT,
    example_translation TEXT,
    category            VOCAB_CATEGORY NOT NULL,
    status              VOCAB_STATUS     DEFAULT 'NEW',
    next_review_at      TIMESTAMPTZ,
    review_count        INT              DEFAULT 0,
    added_at            TIMESTAMPTZ      DEFAULT now(),
    UNIQUE (student_id, german)
);

CREATE INDEX idx_vocab_student ON vocabulary_words (student_id);
CREATE INDEX idx_vocab_status ON vocabulary_words (status);
CREATE INDEX idx_vocab_review ON vocabulary_words (next_review_at);

-- Learning goals
CREATE TABLE learning_goals
(
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id  UUID        NOT NULL REFERENCES users (id),
    teacher_id  UUID REFERENCES users (id),
    description TEXT        NOT NULL,
    progress    INT              DEFAULT 0 CHECK (progress >= 0 AND progress <= 100),
    set_by      GOAL_SET_BY NOT NULL,
    target_date DATE,
    status      GOAL_STATUS      DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ      DEFAULT now(),
    updated_at  TIMESTAMPTZ      DEFAULT now()
);

CREATE INDEX idx_goals_student ON learning_goals (student_id);
CREATE INDEX idx_goals_status ON learning_goals (status);

-- Materials / resources
CREATE TABLE materials
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lesson_id  UUID          REFERENCES lessons (id) ON DELETE SET NULL,
    student_id UUID REFERENCES users (id),
    teacher_id UUID          NOT NULL REFERENCES users (id),
    name         VARCHAR(255)  NOT NULL,
    type         MATERIAL_TYPE NOT NULL,
    url          TEXT          NOT NULL,
    content_type VARCHAR(100),
    file_size    BIGINT,
    created_at   TIMESTAMPTZ      DEFAULT now()
);

CREATE INDEX idx_materials_lesson ON materials (lesson_id);
CREATE INDEX idx_materials_student ON materials (student_id);
CREATE INDEX idx_materials_teacher ON materials (teacher_id);
CREATE INDEX idx_materials_type ON materials (type);

-- Auto-update updated_at trigger
CREATE
OR REPLACE FUNCTION update_updated_at()
   RETURNS TRIGGER AS $$
BEGIN
       NEW.updated_at
= now();
RETURN NEW;
END;
   $$
LANGUAGE plpgsql;

CREATE TRIGGER trg_users_updated
    BEFORE UPDATE
    ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
CREATE TRIGGER trg_lessons_updated
    BEFORE UPDATE
    ON lessons
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
CREATE TRIGGER trg_lesson_docs_updated
    BEFORE UPDATE
    ON lesson_documents
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
CREATE TRIGGER trg_homework_updated
    BEFORE UPDATE
    ON homework
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
CREATE TRIGGER trg_goals_updated
    BEFORE UPDATE
    ON learning_goals
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TABLE registrations
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email      VARCHAR(255) NOT NULL,
    token      VARCHAR(255) NOT NULL,
    invited_by    UUID         REFERENCES users (id) ON DELETE SET NULL,
    used       BOOLEAN          DEFAULT FALSE,
    expires_at TIMESTAMPTZ  NOT NULL,
    created_at TIMESTAMPTZ      DEFAULT now(),
    role          USER_ROLE    NOT NULL
);

CREATE INDEX idx_registrations_email ON registrations (email);

CREATE TABLE password_resets
(
    id         UUID PRIMARY KEY            DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token      UUID        NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    used       BOOLEAN                     DEFAULT FALSE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ                 DEFAULT now()
);

CREATE INDEX idx_password_resets_token ON password_resets (token);
CREATE INDEX idx_password_resets_user ON password_resets (user_id);

-- Notification types
CREATE TYPE NOTIFICATION_TYPE AS ENUM (
    'LESSON_CREATED',
    'LESSON_REQUESTED',
    'LESSON_ACCEPTED',
    'LESSON_CANCELLED',
    'RESCHEDULE_REQUESTED',
    'RESCHEDULE_ACCEPTED',
    'RESCHEDULE_REJECTED',
    'JOIN_REQUESTED',
    'JOIN_ACCEPTED',
    'JOIN_REJECTED',
    'LESSON_STARTED',
    'LESSON_COMPLETED'
);

-- Notifications
CREATE TABLE notifications
(
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID              NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    actor_id     UUID              NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type         NOTIFICATION_TYPE NOT NULL,
    reference_id UUID,
    viewed       BOOLEAN           DEFAULT FALSE,
    created_at   TIMESTAMPTZ       DEFAULT now()
);

CREATE INDEX idx_notifications_user_created ON notifications (user_id, created_at DESC);
CREATE INDEX idx_notifications_unread ON notifications (user_id) WHERE viewed = FALSE;
