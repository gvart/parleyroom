-- Teacher library: topic tree + grammar topics. Both are per teacher and start empty.

CREATE TABLE topics
(
    id         UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    teacher_id UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    parent_id  UUID                  REFERENCES topics (id) ON DELETE RESTRICT,
    name       VARCHAR(255) NOT NULL,
    -- Optional CEFR levels the topic is relevant for (A1..C2).
    levels     VARCHAR(2)[] NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_topics_teacher ON topics (teacher_id);
CREATE INDEX idx_topics_parent ON topics (parent_id);

-- Unique sibling names per teacher (same pattern as material_folders).
CREATE UNIQUE INDEX uq_topics_name_in_parent
    ON topics (teacher_id, parent_id, lower(name))
    WHERE parent_id IS NOT NULL;

CREATE UNIQUE INDEX uq_topics_name_at_root
    ON topics (teacher_id, lower(name))
    WHERE parent_id IS NULL;

CREATE TRIGGER trg_topics_updated
    BEFORE UPDATE
    ON topics
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TABLE grammar_topics
(
    id          UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    teacher_id  UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name        VARCHAR(255) NOT NULL,
    level       LANGUAGE_LEVEL,
    category    VARCHAR(100),
    explanation TEXT,
    examples    TEXT[]       NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_grammar_topics_teacher_level ON grammar_topics (teacher_id, level);
CREATE UNIQUE INDEX uq_grammar_topics_name ON grammar_topics (teacher_id, lower(name));

CREATE TRIGGER trg_grammar_topics_updated
    BEFORE UPDATE
    ON grammar_topics
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
