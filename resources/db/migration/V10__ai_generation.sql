-- P4 Nachbereitung: AI generation jobs (async, polled by clients) and the teacher's saved prompts.

CREATE TYPE GENERATION_JOB_KIND AS ENUM ('GENERATE', 'REFINE', 'FILL_TRANSLATIONS');
CREATE TYPE GENERATION_JOB_STATUS AS ENUM ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED');

CREATE TABLE generation_jobs
(
    id                UUID PRIMARY KEY               DEFAULT gen_random_uuid(),
    teacher_id        UUID                  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- Null for FILL_TRANSLATIONS (not tied to a lesson).
    lesson_id         UUID REFERENCES lessons (id) ON DELETE CASCADE,
    kind              GENERATION_JOB_KIND   NOT NULL,
    status            GENERATION_JOB_STATUS NOT NULL DEFAULT 'QUEUED',
    -- REFINE: the job whose result is refined.
    parent_job_id     UUID REFERENCES generation_jobs (id) ON DELETE SET NULL,
    -- The draft document created by GENERATE (inherited by REFINE).
    document_id       UUID REFERENCES documents (id) ON DELETE SET NULL,
    -- Request as sent by the teacher (notes, prompt, instruction, entry ids, ...).
    input             JSONB                 NOT NULL,
    -- Validated, post-processed output (never raw model text).
    result            JSONB,
    error_code        VARCHAR(64),
    error_message     TEXT,
    model             VARCHAR(128),
    attempts          INT                   NOT NULL DEFAULT 0,
    input_tokens      INT,
    output_tokens     INT,
    created_at        TIMESTAMPTZ           NOT NULL DEFAULT now(),
    started_at        TIMESTAMPTZ,
    finished_at       TIMESTAMPTZ,
    published_at      TIMESTAMPTZ
);

CREATE INDEX idx_generation_jobs_lesson ON generation_jobs (lesson_id, created_at DESC);
CREATE INDEX idx_generation_jobs_teacher_status ON generation_jobs (teacher_id, status);

CREATE TYPE PROMPT_TEMPLATE_LESSON_TYPE AS ENUM ('ONE_ON_ONE', 'CLUB');

CREATE TABLE prompt_templates
(
    id          UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    teacher_id  UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name        VARCHAR(100) NOT NULL,
    text        TEXT         NOT NULL,
    level       LANGUAGE_LEVEL,
    lesson_type PROMPT_TEMPLATE_LESSON_TYPE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_prompt_templates_name ON prompt_templates (teacher_id, lower(name));

CREATE TRIGGER trg_prompt_templates_updated
    BEFORE UPDATE
    ON prompt_templates
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- Snapshot taken before an AI refine overwrites a draft's blocks.
ALTER TYPE DOCUMENT_VERSION_REASON ADD VALUE 'AI_REFINE';
