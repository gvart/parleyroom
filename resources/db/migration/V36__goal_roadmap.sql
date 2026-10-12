-- Stage 1 goals: one goal per student with a teacher-edited (AI-drafted) roadmap of milestones and
-- items, progress from checked items, student self-assessment per finished milestone.
-- Replaces the auto-tracked goals of V14 (no data migration, decision D-001).

DROP TABLE goals;
DROP TYPE GOAL_TYPE;
DROP TYPE GOAL_STATUS;

CREATE TYPE GOAL_STATUS AS ENUM ('ACTIVE', 'ACHIEVED', 'ARCHIVED');
CREATE TYPE ROADMAP_ITEM_KIND AS ENUM ('TOPIC', 'GRAMMAR', 'VOCAB', 'SKILL', 'EXAM');
CREATE TYPE ROADMAP_ITEM_STATE AS ENUM ('NOT_STARTED', 'IN_PROGRESS', 'DONE');
CREATE TYPE SELF_ASSESSMENT_ANSWER AS ENUM ('YES', 'PARTLY', 'NOT_YET');

CREATE TABLE goals
(
    id               UUID PRIMARY KEY        DEFAULT gen_random_uuid(),
    student_id       UUID           NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    teacher_id       UUID REFERENCES users (id) ON DELETE SET NULL,
    title            VARCHAR(200)   NOT NULL,
    exam             VARCHAR(100),
    target_level     LANGUAGE_LEVEL,
    start_date       DATE           NOT NULL,
    deadline         DATE           NOT NULL,
    lessons_per_week NUMERIC(3, 1)  NOT NULL,
    lesson_minutes   INT            NOT NULL,
    level_note       TEXT,
    interests        TEXT,
    materials        TEXT,
    status           GOAL_STATUS    NOT NULL DEFAULT 'ACTIVE',
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT chk_goal_dates CHECK (deadline > start_date),
    CONSTRAINT chk_goal_lessons CHECK (lessons_per_week > 0 AND lesson_minutes > 0)
);

CREATE UNIQUE INDEX uq_goals_active_student ON goals (student_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_goals_teacher ON goals (teacher_id, status);

CREATE TABLE roadmap_milestones
(
    id             UUID PRIMARY KEY       DEFAULT gen_random_uuid(),
    goal_id        UUID          NOT NULL REFERENCES goals (id) ON DELETE CASCADE,
    position       INT           NOT NULL,
    title          VARCHAR(200)  NOT NULL,
    start_date     DATE          NOT NULL,
    end_date       DATE          NOT NULL,
    weight         NUMERIC(6, 2) NOT NULL DEFAULT 1,
    topic          TEXT,
    grammar_points TEXT[]        NOT NULL DEFAULT '{}',
    word_fields    TEXT[]        NOT NULL DEFAULT '{}',
    exam_part      TEXT,
    teacher_tips   TEXT,
    can_do         TEXT          NOT NULL,
    done_at        TIMESTAMPTZ,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_milestone_dates CHECK (end_date >= start_date),
    CONSTRAINT chk_milestone_weight CHECK (weight > 0)
);

CREATE INDEX idx_roadmap_milestones_goal ON roadmap_milestones (goal_id, position);

CREATE TABLE roadmap_items
(
    id               UUID PRIMARY KEY            DEFAULT gen_random_uuid(),
    milestone_id     UUID               NOT NULL REFERENCES roadmap_milestones (id) ON DELETE CASCADE,
    position         INT                NOT NULL,
    kind             ROADMAP_ITEM_KIND  NOT NULL,
    title            VARCHAR(300)       NOT NULL,
    state            ROADMAP_ITEM_STATE NOT NULL DEFAULT 'NOT_STARTED',
    state_changed_at TIMESTAMPTZ,
    -- The lesson in which the item was last checked.
    lesson_id        UUID REFERENCES lessons (id) ON DELETE SET NULL,
    created_at       TIMESTAMPTZ        NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ        NOT NULL DEFAULT now()
);

CREATE INDEX idx_roadmap_items_milestone ON roadmap_items (milestone_id, position);

CREATE TABLE milestone_self_assessments
(
    id           UUID PRIMARY KEY                DEFAULT gen_random_uuid(),
    milestone_id UUID                   NOT NULL REFERENCES roadmap_milestones (id) ON DELETE CASCADE,
    student_id   UUID                   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    answer       SELF_ASSESSMENT_ANSWER NOT NULL,
    comment      VARCHAR(1000),
    created_at   TIMESTAMPTZ            NOT NULL DEFAULT now(),
    CONSTRAINT uq_self_assessment UNIQUE (milestone_id, student_id)
);

-- Roadmap generation jobs belong to a goal.
ALTER TYPE GENERATION_JOB_KIND ADD VALUE 'ROADMAP';
ALTER TYPE GENERATION_JOB_KIND ADD VALUE 'MILESTONE_REGEN';
ALTER TABLE generation_jobs ADD COLUMN goal_id UUID REFERENCES goals (id) ON DELETE CASCADE;
CREATE INDEX idx_generation_jobs_goal ON generation_jobs (goal_id) WHERE goal_id IS NOT NULL;

INSERT INTO ai_model_settings (use_case, model)
VALUES ('ROADMAP', 'claude-sonnet-5-5'),
       ('MILESTONE_REGEN', 'claude-sonnet-5-5'),
       ('LESSON_PREP', 'claude-sonnet-5-5');

ALTER TYPE NOTIFICATION_TYPE ADD VALUE 'MILESTONE_COMPLETED';
ALTER TYPE NOTIFICATION_TYPE ADD VALUE 'SELF_ASSESSMENT_MISMATCH';
