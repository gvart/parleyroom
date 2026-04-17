ALTER TABLE users ADD COLUMN telegram_id BIGINT;
ALTER TABLE users ADD COLUMN telegram_username VARCHAR(64);
CREATE UNIQUE INDEX users_telegram_id_uidx ON users(telegram_id) WHERE telegram_id IS NOT NULL;
