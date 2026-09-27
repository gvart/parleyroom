-- Vocabulary v2: a per-teacher library of VocabEntries (deduplicated per teacher)
-- linked to students through student_vocab. Supersedes vocabulary_words and
-- lesson_words, which are no longer read or written by the application.

CREATE TYPE WORD_TYPE AS ENUM ('NOUN', 'VERB', 'ADJECTIVE', 'ADVERB', 'PREPOSITION', 'CONJUNCTION', 'PRONOUN', 'PHRASE', 'OTHER');
CREATE TYPE NOUN_ARTICLE AS ENUM ('DER', 'DIE', 'DAS');
CREATE TYPE STUDENT_VOCAB_STATUS AS ENUM ('NEW', 'LEARNING', 'REVIEW', 'LEARNED');

CREATE TABLE vocab_entries
(
    id               UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    teacher_id       UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    lemma            VARCHAR(255) NOT NULL,
    article          NOUN_ARTICLE,
    plural           VARCHAR(255),
    word_type        WORD_TYPE    NOT NULL,
    forms            TEXT,
    government       TEXT,
    -- Language code -> translation, e.g. {"ru": "...", "en": "..."}. JSONB so new
    -- languages need no migration; always read together with the entry.
    translations     JSONB        NOT NULL DEFAULT '{}',
    explanation_de   TEXT,
    example_sentence TEXT,
    level            LANGUAGE_LEVEL,
    synonyms         TEXT[]       NOT NULL DEFAULT '{}',
    source_lesson_id UUID         REFERENCES lessons (id) ON DELETE SET NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_vocab_entries_teacher_level ON vocab_entries (teacher_id, level);

-- Dedupe key within a teacher's library: lemma (case-insensitive) + article + word type.
CREATE UNIQUE INDEX uq_vocab_entries_with_article
    ON vocab_entries (teacher_id, lower(lemma), article, word_type)
    WHERE article IS NOT NULL;

CREATE UNIQUE INDEX uq_vocab_entries_without_article
    ON vocab_entries (teacher_id, lower(lemma), word_type)
    WHERE article IS NULL;

CREATE TRIGGER trg_vocab_entries_updated
    BEFORE UPDATE
    ON vocab_entries
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TABLE vocab_entry_topics
(
    vocab_entry_id UUID NOT NULL REFERENCES vocab_entries (id) ON DELETE CASCADE,
    topic_id       UUID NOT NULL REFERENCES topics (id) ON DELETE CASCADE,
    PRIMARY KEY (vocab_entry_id, topic_id)
);

CREATE INDEX idx_vocab_entry_topics_topic ON vocab_entry_topics (topic_id);

-- A student's copy of an entry. FSRS columns are created now; the scheduler lands later.
CREATE TABLE student_vocab
(
    id             UUID PRIMARY KEY              DEFAULT gen_random_uuid(),
    student_id     UUID                 NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    vocab_entry_id UUID                 NOT NULL REFERENCES vocab_entries (id) ON DELETE CASCADE,
    -- Lesson in which this student received the word (drives the per-lesson display override).
    lesson_id      UUID                 REFERENCES lessons (id) ON DELETE SET NULL,
    status         STUDENT_VOCAB_STATUS NOT NULL DEFAULT 'NEW',
    due            TIMESTAMPTZ,
    stability      DOUBLE PRECISION,
    difficulty     DOUBLE PRECISION,
    elapsed_days   INT                  NOT NULL DEFAULT 0,
    scheduled_days INT                  NOT NULL DEFAULT 0,
    reps           INT                  NOT NULL DEFAULT 0,
    lapses         INT                  NOT NULL DEFAULT 0,
    -- FSRS card state: 0 new, 1 learning, 2 review, 3 relearning.
    state          SMALLINT             NOT NULL DEFAULT 0,
    last_review    TIMESTAMPTZ,
    added_at       TIMESTAMPTZ          NOT NULL DEFAULT now(),
    UNIQUE (student_id, vocab_entry_id)
);

CREATE INDEX idx_student_vocab_student_status ON student_vocab (student_id, status);
CREATE INDEX idx_student_vocab_due ON student_vocab (due);
CREATE INDEX idx_student_vocab_entry ON student_vocab (vocab_entry_id);
CREATE INDEX idx_student_vocab_lesson ON student_vocab (lesson_id);

-- Words introduced in a lesson (supersedes lesson_words).
CREATE TABLE lesson_vocab
(
    lesson_id      UUID NOT NULL REFERENCES lessons (id) ON DELETE CASCADE,
    vocab_entry_id UUID NOT NULL REFERENCES vocab_entries (id) ON DELETE CASCADE,
    order_index    INT  NOT NULL DEFAULT 0,
    PRIMARY KEY (lesson_id, vocab_entry_id)
);

CREATE INDEX idx_lesson_vocab_entry ON lesson_vocab (vocab_entry_id);

-- Vocab display setting: which of ru | en | de_explanation a student sees, and whether
-- they may reveal the hidden translation. NULL = derive from the student's level.
ALTER TABLE teacher_students
    ADD COLUMN vocab_display_fields     VARCHAR(32)[],
    ADD COLUMN allow_translation_toggle BOOLEAN;

-- Per-lesson override of the same setting (NULL = no override).
ALTER TABLE lessons
    ADD COLUMN vocab_display_fields     VARCHAR(32)[],
    ADD COLUMN allow_translation_toggle BOOLEAN;
