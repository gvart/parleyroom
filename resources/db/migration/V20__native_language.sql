-- Russian-first: new users get the Russian interface; existing users keep their locale.
ALTER TABLE users ALTER COLUMN locale SET DEFAULT 'ru';

-- Language a student gets translations and explanations in (ru | uk | en); null for teachers/admins.
ALTER TABLE users
    ADD COLUMN native_language     VARCHAR(5),
    ADD COLUMN locale_confirmed_at TIMESTAMPTZ;

UPDATE users SET native_language = 'ru' WHERE role = 'STUDENT';

-- Set by the inviting teacher/admin, copied to the user on registration (student invites only).
ALTER TABLE registrations ADD COLUMN native_language VARCHAR(5);
