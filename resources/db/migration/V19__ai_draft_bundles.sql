-- AI draft bundles (human in the loop): AI output stays in teacher-only draft tables until the
-- teacher sends it. Real student rows (student_vocab, assignments/homework, document shares) are
-- only created by Send, so no student-facing query needs to know about drafts.

CREATE TYPE AI_DRAFT_SCOPE AS ENUM ('LESSON', 'STUDENT');
CREATE TYPE AI_DRAFT_MODE AS ENUM ('ONE_ON_ONE', 'CLUB');
CREATE TYPE AI_DRAFT_STATUS AS ENUM ('DRAFT', 'SENT', 'DISCARDED');
CREATE TYPE AI_DRAFT_ITEM_KIND AS ENUM ('WORD', 'EXERCISE_DOCUMENT', 'TASK', 'NOTES_DOCUMENT');

CREATE TABLE ai_draft_bundles
(
    id          UUID PRIMARY KEY          DEFAULT gen_random_uuid(),
    teacher_id  UUID             NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    scope       AI_DRAFT_SCOPE   NOT NULL,
    -- LESSON: the post-lesson follow-up. STUDENT: out-of-lesson "new homework" / "add words".
    lesson_id   UUID REFERENCES lessons (id) ON DELETE CASCADE,
    student_id  UUID REFERENCES users (id) ON DELETE CASCADE,
    mode        AI_DRAFT_MODE    NOT NULL,
    status      AI_DRAFT_STATUS  NOT NULL DEFAULT 'DRAFT',
    -- What the teacher asked for (notes, prompt, template, context sources).
    input       JSONB            NOT NULL,
    -- What Send created; returned again on a repeated Send (idempotency).
    send_result JSONB,
    sent_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ      NOT NULL DEFAULT now(),
    CONSTRAINT ck_ai_draft_bundles_scope CHECK (
        (scope = 'LESSON' AND lesson_id IS NOT NULL) OR (scope = 'STUDENT' AND student_id IS NOT NULL)
    )
);

-- One open draft per lesson and per (teacher, student): generating again reuses it.
CREATE UNIQUE INDEX uq_ai_draft_bundles_lesson ON ai_draft_bundles (lesson_id)
    WHERE status = 'DRAFT' AND scope = 'LESSON';
CREATE UNIQUE INDEX uq_ai_draft_bundles_student ON ai_draft_bundles (teacher_id, student_id)
    WHERE status = 'DRAFT' AND scope = 'STUDENT';
CREATE INDEX idx_ai_draft_bundles_teacher ON ai_draft_bundles (teacher_id, status, updated_at DESC);

CREATE TRIGGER trg_ai_draft_bundles_updated
    BEFORE UPDATE
    ON ai_draft_bundles
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TABLE ai_draft_items
(
    id         UUID PRIMARY KEY            DEFAULT gen_random_uuid(),
    bundle_id  UUID               NOT NULL REFERENCES ai_draft_bundles (id) ON DELETE CASCADE,
    kind       AI_DRAFT_ITEM_KIND NOT NULL,
    position   INT                NOT NULL,
    approved   BOOLEAN            NOT NULL DEFAULT FALSE,
    -- Kind-specific content incl. suggested topic / grammar tags (see API.md "AI drafts").
    payload    JSONB              NOT NULL,
    created_at TIMESTAMPTZ        NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ        NOT NULL DEFAULT now()
);

CREATE INDEX idx_ai_draft_items_bundle ON ai_draft_items (bundle_id, position);

CREATE TRIGGER trg_ai_draft_items_updated
    BEFORE UPDATE
    ON ai_draft_items
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- The bundle a GENERATE / REFINE job works on.
ALTER TABLE generation_jobs
    ADD COLUMN bundle_id UUID REFERENCES ai_draft_bundles (id) ON DELETE SET NULL;
CREATE INDEX idx_generation_jobs_bundle ON generation_jobs (bundle_id, created_at DESC);

-- An AI refine of an existing document lands here, never in documents: students keep reading
-- documents.blocks until the teacher publishes (or discards) the draft revision.
CREATE TABLE document_drafts
(
    document_id   UUID PRIMARY KEY REFERENCES documents (id) ON DELETE CASCADE,
    title         VARCHAR(255) NOT NULL,
    blocks        JSONB        NOT NULL,
    -- documents.revision the draft was made from.
    base_revision INT          NOT NULL,
    job_id        UUID REFERENCES generation_jobs (id) ON DELETE SET NULL,
    created_by    UUID REFERENCES users (id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TRIGGER trg_document_drafts_updated
    BEFORE UPDATE
    ON document_drafts
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
