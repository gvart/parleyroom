-- Who cancelled a lesson, when, and why. cancelled_at bounds the short un-cancel window.
ALTER TABLE lessons
    ADD COLUMN cancel_reason TEXT,
    ADD COLUMN cancelled_by  UUID REFERENCES users (id),
    ADD COLUMN cancelled_at  TIMESTAMPTZ;

-- A notification is hidden (and not pushed) until deliver_after; NULL means immediately.
-- Used to hold LESSON_CANCELLED back until the un-cancel window has passed.
ALTER TABLE notifications
    ADD COLUMN deliver_after TIMESTAMPTZ;
