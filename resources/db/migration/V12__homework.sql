-- P6 Homework in the app: assignments with items, one homework instance per student,
-- answers per answerable unit, uploads. Replaces the old text-submission homework.

DROP TABLE homework;
DROP TYPE HOMEWORK_STATUS;
DROP TYPE HOMEWORK_CATEGORY;
DROP TYPE ATTACHMENT_TYPE;

CREATE TYPE HOMEWORK_STATUS AS ENUM ('OPEN', 'SUBMITTED', 'REVIEWED', 'DONE');
CREATE TYPE HOMEWORK_OUTCOME AS ENUM ('REVIEWED', 'RETURNED', 'DONE');
CREATE TYPE ASSIGNMENT_ITEM_KIND AS ENUM ('DOCUMENT', 'MATERIAL', 'TASK');
CREATE TYPE HOMEWORK_RESPONSE_TYPE AS ENUM ('TEXT', 'AUDIO', 'VIDEO', 'FILE');
CREATE TYPE HOMEWORK_AUTO_RESULT AS ENUM ('CORRECT', 'INCORRECT', 'PENDING_REVIEW', 'UNANSWERED');

ALTER TYPE NOTIFICATION_TYPE ADD VALUE 'HOMEWORK_ASSIGNED';
ALTER TYPE NOTIFICATION_TYPE ADD VALUE 'HOMEWORK_SUBMITTED';
ALTER TYPE NOTIFICATION_TYPE ADD VALUE 'HOMEWORK_REVIEWED';
ALTER TYPE NOTIFICATION_TYPE ADD VALUE 'HOMEWORK_RETURNED';

CREATE TABLE assignments
(
    id           UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    teacher_id   UUID         NOT NULL REFERENCES users (id),
    lesson_id    UUID                  REFERENCES lessons (id) ON DELETE SET NULL,
    title        VARCHAR(255) NOT NULL,
    instructions TEXT,
    due_date     DATE,
    -- Items are immutable after create, so their counts are stored for cheap lists.
    item_count   INT          NOT NULL,
    total_units  INT          NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_assignments_teacher ON assignments (teacher_id, created_at DESC);
CREATE INDEX idx_assignments_lesson ON assignments (lesson_id);

CREATE TRIGGER trg_assignments_updated
    BEFORE UPDATE
    ON assignments
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- Groups the assignment was given to (members were expanded at assign time).
CREATE TABLE assignment_groups
(
    assignment_id UUID NOT NULL REFERENCES assignments (id) ON DELETE CASCADE,
    group_id      UUID NOT NULL REFERENCES study_groups (id) ON DELETE CASCADE,
    PRIMARY KEY (assignment_id, group_id)
);

-- DOCUMENT items keep a snapshot of the document (blocks incl. solutions) taken at assign time.
CREATE TABLE assignment_items
(
    id                UUID PRIMARY KEY              DEFAULT gen_random_uuid(),
    assignment_id     UUID                 NOT NULL REFERENCES assignments (id) ON DELETE CASCADE,
    position          INT                  NOT NULL,
    kind              ASSIGNMENT_ITEM_KIND NOT NULL,
    title             VARCHAR(255)         NOT NULL,
    task              TEXT,
    response_type     HOMEWORK_RESPONSE_TYPE,
    document_id       UUID                 REFERENCES documents (id) ON DELETE SET NULL,
    document_revision INT,
    blocks            JSONB,
    material_id       UUID                 REFERENCES materials (id) ON DELETE SET NULL,
    UNIQUE (assignment_id, position)
);

CREATE INDEX idx_assignment_items_material ON assignment_items (material_id);

CREATE TABLE homework
(
    id             UUID PRIMARY KEY          DEFAULT gen_random_uuid(),
    assignment_id  UUID             NOT NULL REFERENCES assignments (id) ON DELETE CASCADE,
    student_id     UUID             NOT NULL REFERENCES users (id),
    status         HOMEWORK_STATUS  NOT NULL DEFAULT 'OPEN',
    last_outcome   HOMEWORK_OUTCOME,
    attempt        INT              NOT NULL DEFAULT 0,
    feedback       TEXT,
    closed_correct INT,
    closed_total   INT,
    pending_review INT,
    unanswered     INT,
    last_saved_at  TIMESTAMPTZ,
    submitted_at   TIMESTAMPTZ,
    reviewed_at    TIMESTAMPTZ,
    returned_at    TIMESTAMPTZ,
    done_at        TIMESTAMPTZ,
    created_at     TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ      NOT NULL DEFAULT now(),
    UNIQUE (assignment_id, student_id)
);

CREATE INDEX idx_homework_student_status ON homework (student_id, status);
CREATE INDEX idx_homework_status_submitted ON homework (status, submitted_at);

CREATE TRIGGER trg_homework_updated
    BEFORE UPDATE
    ON homework
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- One row per answered unit: DOCUMENT units are (item, block_id, item_ref), MATERIAL/TASK units are (item).
CREATE TABLE homework_answers
(
    id                 UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    homework_id        UUID        NOT NULL REFERENCES homework (id) ON DELETE CASCADE,
    assignment_item_id UUID        NOT NULL REFERENCES assignment_items (id) ON DELETE CASCADE,
    block_id           UUID,
    item_ref           UUID,
    answer             JSONB,
    -- Set only when the answer is non-empty; drives answeredUnits.
    answered_at        TIMESTAMPTZ,
    -- The answer as last submitted (a changed answer on resubmit clears the teacher's verdict).
    submitted_answer   JSONB,
    auto_result        HOMEWORK_AUTO_RESULT,
    auto_score         DOUBLE PRECISION,
    case_mismatch      BOOLEAN     NOT NULL DEFAULT false,
    gap_results        JSONB,
    teacher_correct    BOOLEAN,
    comment            TEXT,
    CONSTRAINT chk_homework_answers_unit CHECK ((block_id IS NULL) = (item_ref IS NULL))
);

CREATE UNIQUE INDEX uq_homework_answers_doc_unit
    ON homework_answers (homework_id, assignment_item_id, block_id, item_ref) WHERE block_id IS NOT NULL;
CREATE UNIQUE INDEX uq_homework_answers_item_unit
    ON homework_answers (homework_id, assignment_item_id) WHERE block_id IS NULL;

CREATE TABLE homework_uploads
(
    id                 UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    homework_id        UUID         NOT NULL REFERENCES homework (id) ON DELETE CASCADE,
    assignment_item_id UUID         NOT NULL REFERENCES assignment_items (id) ON DELETE CASCADE,
    storage_key        VARCHAR(512) NOT NULL,
    file_name          VARCHAR(255) NOT NULL,
    content_type       VARCHAR(100) NOT NULL,
    size               BIGINT       NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_homework_uploads_homework ON homework_uploads (homework_id, assignment_item_id);
