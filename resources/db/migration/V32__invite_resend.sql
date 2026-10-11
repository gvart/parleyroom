-- Emailed invites: when the invite email last went out (successful sends only; null = never delivered,
-- e.g. a provider failure where the inviter copied the link instead). Resends rotate the token in place.
ALTER TABLE registrations ADD COLUMN last_sent_at TIMESTAMPTZ;

-- Teachers list the invites they sent.
CREATE INDEX idx_registrations_invited_by ON registrations (invited_by);
