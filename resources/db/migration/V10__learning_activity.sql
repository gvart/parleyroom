-- Append-only log of student learning actions. Feeds the daily learning
-- streak: a day (in the user's timezone) is active when it has >= 1 row.
CREATE TABLE learning_activity
(
    id          UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind        VARCHAR(32) NOT NULL,
    ref_id      UUID,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_la_kind CHECK (kind IN ('VOCAB_REVIEW', 'LESSON_COMPLETED', 'HOMEWORK_SUBMITTED'))
);

CREATE INDEX idx_la_user_occurred ON learning_activity (user_id, occurred_at);

-- Backfill: completed lessons, one row per confirmed student participant,
-- at the moment the lesson was completed (ended_at is set by completion).
INSERT INTO learning_activity (user_id, kind, ref_id, occurred_at)
SELECT ls.student_id, 'LESSON_COMPLETED', l.id, COALESCE(l.ended_at, l.updated_at, l.scheduled_at)
FROM lessons l
         JOIN lesson_students ls ON ls.lesson_id = l.id
WHERE l.status = 'COMPLETED'
  AND ls.status = 'CONFIRMED';

-- Backfill: submitted homework. There is no submitted_at column; updated_at
-- is the submission time only while the homework is still awaiting review.
-- Reviewed homework (DONE / REJECTED) has updated_at = review time, which
-- would credit the wrong day, so it is skipped.
INSERT INTO learning_activity (user_id, kind, ref_id, occurred_at)
SELECT h.student_id, 'HOMEWORK_SUBMITTED', h.id, COALESCE(h.updated_at, h.created_at, now())
FROM homework h
WHERE h.status IN ('SUBMITTED', 'IN_REVIEW');

-- Vocabulary reviews kept no history before this migration; nothing to backfill.
