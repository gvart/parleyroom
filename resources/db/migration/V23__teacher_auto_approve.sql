-- Teacher approval preferences; both off keeps the manual approval flow.
ALTER TABLE users
    ADD COLUMN auto_confirm_bookings  BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN auto_accept_club_joins BOOLEAN NOT NULL DEFAULT FALSE;

-- Tell the teacher about what was approved on their behalf.
ALTER TYPE NOTIFICATION_TYPE ADD VALUE 'LESSON_BOOKED';
ALTER TYPE NOTIFICATION_TYPE ADD VALUE 'CLUB_JOINED';
