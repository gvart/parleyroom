-- Stage 2 lesson preparation: an AI-drafted plan per student lesson (teacher only) and what was
-- actually covered, which together form the lesson history that feeds the next preparation.

CREATE TYPE LESSON_PLAN_STATUS AS ENUM ('DRAFT', 'READY', 'DONE');

CREATE TABLE lesson_plans
(
    id                 UUID PRIMARY KEY            DEFAULT gen_random_uuid(),
    student_id         UUID               NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    teacher_id         UUID               NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    goal_id            UUID REFERENCES goals (id) ON DELETE SET NULL,
    -- The scheduled lesson this preparation is for.
    lesson_id          UUID REFERENCES lessons (id) ON DELETE SET NULL,
    prep_note          TEXT               NOT NULL,
    explain_in_russian BOOLEAN            NOT NULL DEFAULT false,
    -- Null until the first generation succeeds.
    plan               JSONB,
    covered_note       TEXT,
    status             LESSON_PLAN_STATUS NOT NULL DEFAULT 'DRAFT',
    created_at         TIMESTAMPTZ        NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ        NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_lesson_plans_lesson ON lesson_plans (lesson_id) WHERE lesson_id IS NOT NULL;
CREATE INDEX idx_lesson_plans_student ON lesson_plans (student_id, teacher_id);

ALTER TYPE GENERATION_JOB_KIND ADD VALUE 'LESSON_PREP';
ALTER TABLE generation_jobs ADD COLUMN lesson_plan_id UUID REFERENCES lesson_plans (id) ON DELETE SET NULL;
CREATE INDEX idx_generation_jobs_lesson_plan ON generation_jobs (lesson_plan_id) WHERE lesson_plan_id IS NOT NULL;
