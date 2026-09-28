-- Block documents: an ordered JSONB array of typed blocks owned by a teacher (their library),
-- shared with students / groups and linked to lessons.

CREATE TYPE DOCUMENT_AUDIENCE AS ENUM ('STUDENT', 'GROUP', 'LIBRARY');
CREATE TYPE DOCUMENT_VERSION_REASON AS ENUM ('AUTOSAVE', 'SHARE', 'RESTORE', 'DUPLICATE');

CREATE TABLE documents
(
    id                     UUID PRIMARY KEY           DEFAULT gen_random_uuid(),
    owner_id               UUID              NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title                  VARCHAR(255)      NOT NULL,
    level                  LANGUAGE_LEVEL,
    audience               DOCUMENT_AUDIENCE NOT NULL,
    -- Validated against resources/document-blocks.schema.json on every write.
    blocks                 JSONB             NOT NULL DEFAULT '[]',
    created_from_lesson_id UUID              REFERENCES lessons (id) ON DELETE SET NULL,
    created_at             TIMESTAMPTZ       NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ       NOT NULL DEFAULT now()
);

CREATE INDEX idx_documents_owner_updated ON documents (owner_id, updated_at DESC);

CREATE TRIGGER trg_documents_updated
    BEFORE UPDATE
    ON documents
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TABLE document_topics
(
    document_id UUID NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    topic_id    UUID NOT NULL REFERENCES topics (id) ON DELETE CASCADE,
    PRIMARY KEY (document_id, topic_id)
);

CREATE INDEX idx_document_topics_topic ON document_topics (topic_id);

CREATE TABLE document_grammar_topics
(
    document_id      UUID NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    grammar_topic_id UUID NOT NULL REFERENCES grammar_topics (id) ON DELETE CASCADE,
    PRIMARY KEY (document_id, grammar_topic_id)
);

CREATE INDEX idx_document_grammar_topics_grammar ON document_grammar_topics (grammar_topic_id);

CREATE TABLE document_students
(
    document_id UUID        NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    student_id  UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    shared_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (document_id, student_id)
);

CREATE INDEX idx_document_students_student ON document_students (student_id);

CREATE TABLE document_groups
(
    document_id UUID        NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    group_id    UUID        NOT NULL REFERENCES study_groups (id) ON DELETE CASCADE,
    shared_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (document_id, group_id)
);

CREATE INDEX idx_document_groups_group ON document_groups (group_id);

CREATE TABLE document_lessons
(
    document_id UUID        NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    lesson_id   UUID        NOT NULL REFERENCES lessons (id) ON DELETE CASCADE,
    linked_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (document_id, lesson_id)
);

CREATE INDEX idx_document_lessons_lesson ON document_lessons (lesson_id);

-- Snapshots of title + blocks taken before a change; the newest 30 per document are kept.
CREATE TABLE document_versions
(
    id          UUID PRIMARY KEY                 DEFAULT gen_random_uuid(),
    document_id UUID                    NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    number      INT                     NOT NULL,
    reason      DOCUMENT_VERSION_REASON NOT NULL,
    title       VARCHAR(255)            NOT NULL,
    blocks      JSONB                   NOT NULL,
    created_by  UUID                    REFERENCES users (id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ             NOT NULL DEFAULT now(),
    UNIQUE (document_id, number)
);

