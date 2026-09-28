-- P8: student progress (grammar overrides) and auto-tracked goals.
-- The old manual-percentage goals are replaced (prod DB is reset).

DROP TABLE learning_goals;
DROP TYPE GOAL_SET_BY;
DROP TYPE GOAL_STATUS;

CREATE TYPE GOAL_TYPE AS ENUM ('EXAM', 'LEVEL');
CREATE TYPE GOAL_STATUS AS ENUM ('ACTIVE', 'ACHIEVED', 'ARCHIVED');
CREATE TYPE GRAMMAR_PROGRESS_STATUS AS ENUM ('NOT_COVERED', 'COVERED', 'PRACTICED', 'NEEDS_WORK');

CREATE TABLE goals
(
    id                UUID PRIMARY KEY        DEFAULT gen_random_uuid(),
    student_id        UUID           NOT NULL REFERENCES users (id),
    teacher_id        UUID           NOT NULL REFERENCES users (id),
    type              GOAL_TYPE      NOT NULL,
    exam_name         VARCHAR(100),
    target_level      LANGUAGE_LEVEL NOT NULL,
    target_date       DATE,
    note              TEXT,
    baseline_percent  INT CHECK (baseline_percent BETWEEN 0 AND 100),
    status            GOAL_STATUS    NOT NULL DEFAULT 'ACTIVE',
    status_changed_at TIMESTAMPTZ,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT chk_goal_exam CHECK (type <> 'EXAM' OR (exam_name IS NOT NULL AND target_date IS NOT NULL)),
    CONSTRAINT chk_goal_level CHECK (type <> 'LEVEL' OR exam_name IS NULL)
);

CREATE INDEX idx_goals_student ON goals (student_id, status);
CREATE INDEX idx_goals_teacher ON goals (teacher_id, status);

CREATE TABLE grammar_progress_overrides
(
    student_id       UUID                    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    grammar_topic_id UUID                    NOT NULL REFERENCES grammar_topics (id) ON DELETE CASCADE,
    teacher_id       UUID                    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status           GRAMMAR_PROGRESS_STATUS NOT NULL,
    note             TEXT,
    updated_at       TIMESTAMPTZ             NOT NULL DEFAULT now(),
    PRIMARY KEY (student_id, grammar_topic_id)
);

CREATE INDEX idx_grammar_overrides_topic ON grammar_progress_overrides (grammar_topic_id);

-- Homework item -> source document tags (progress "practiced").
CREATE INDEX idx_assignment_items_document ON assignment_items (document_id);
