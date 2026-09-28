-- P7 Practice: FSRS scheduling, review log, own sentences with AI feedback.

-- FSRS learning step index (null outside LEARNING / RELEARNING). state: 0 NEW, 1 LEARNING, 2 REVIEW, 3 RELEARNING.
ALTER TABLE student_vocab
    ADD COLUMN step SMALLINT;

-- Cards touched by the pre-FSRS "knew it" review have no stability: start them over as NEW.
UPDATE student_vocab
SET state = 0, step = NULL, due = NULL, reps = 0, lapses = 0, elapsed_days = 0, scheduled_days = 0,
    last_review = NULL, difficulty = NULL,
    status = CASE WHEN status = 'NEW' THEN status ELSE 'LEARNING' END
WHERE stability IS NULL AND state <> 0;

CREATE INDEX idx_student_vocab_student_due ON student_vocab (student_id, state, due);

CREATE TYPE VOCAB_RATING AS ENUM ('AGAIN', 'HARD', 'GOOD', 'EASY');
CREATE TYPE PRACTICE_MODE AS ENUM ('DE_TO_MEANING', 'MEANING_TO_DE', 'ARTICLE');

CREATE TABLE vocab_reviews
(
    id               UUID PRIMARY KEY       DEFAULT gen_random_uuid(),
    student_vocab_id UUID          NOT NULL REFERENCES student_vocab (id) ON DELETE CASCADE,
    student_id       UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    rating           VOCAB_RATING  NOT NULL,
    mode             PRACTICE_MODE NOT NULL,
    -- FSRS state before the review; 0 = the card's first review (daily new-card budget).
    state_before     SMALLINT      NOT NULL,
    response_ms      INT,
    reviewed_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_vocab_reviews_student_time ON vocab_reviews (student_id, reviewed_at);

CREATE TABLE student_vocab_sentences
(
    id               UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    student_vocab_id UUID        NOT NULL REFERENCES student_vocab (id) ON DELETE CASCADE,
    student_id       UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    sentence         TEXT        NOT NULL,
    -- { isCorrect, corrected, explanation, explanationTranslation: { language, text } | null, usesWord }
    feedback         JSONB       NOT NULL,
    model_id         VARCHAR(100),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_sv_sentences_student_time ON student_vocab_sentences (student_id, created_at);
CREATE INDEX idx_sv_sentences_word ON student_vocab_sentences (student_vocab_id, created_at);

ALTER TABLE learning_activity DROP CONSTRAINT chk_la_kind;
ALTER TABLE learning_activity
    ADD CONSTRAINT chk_la_kind CHECK (kind IN ('VOCAB_REVIEW', 'VOCAB_SENTENCE', 'LESSON_COMPLETED', 'HOMEWORK_SUBMITTED'));
