-- Teachers move lessons directly; the move is logged and participants are told.
ALTER TYPE LESSON_EVENT_TYPE ADD VALUE 'LESSON_MOVED';
ALTER TYPE NOTIFICATION_TYPE ADD VALUE 'LESSON_MOVED';

-- Old and new lesson time a notification is about, so it can be shown without a lookup.
ALTER TABLE notifications
    ADD COLUMN old_scheduled_at TIMESTAMPTZ,
    ADD COLUMN new_scheduled_at TIMESTAMPTZ;
