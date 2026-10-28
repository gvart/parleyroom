-- Opt-outs for the daily "words due for review" email and Telegram message.

ALTER TABLE users
    ADD COLUMN email_vocab_reminders    BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN telegram_vocab_reminders BOOLEAN NOT NULL DEFAULT TRUE;
