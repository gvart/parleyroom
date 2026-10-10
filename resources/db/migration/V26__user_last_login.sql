-- Stamped on every successful token issuance (password login, refresh, Telegram Mini App).
ALTER TABLE users ADD COLUMN last_login_at TIMESTAMPTZ NULL;
