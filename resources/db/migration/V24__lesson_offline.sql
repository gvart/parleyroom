-- In-person lessons: no video room, the teacher only takes notes. Existing lessons stay online.
ALTER TABLE lessons ADD COLUMN offline BOOLEAN NOT NULL DEFAULT FALSE;
