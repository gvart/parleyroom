-- Remove the V2 seed fixtures (Anya teacher + Vlad student) and drop the V2
-- row from flyway_schema_history so deleting V2__seed_users.sql from source
-- doesn't leave Flyway flagging a "missing applied migration" on startup.
--
-- Safe to re-run on a fresh database: every statement is idempotent.

DELETE FROM teacher_students WHERE id = 'a0000000-0000-0000-0000-000000000020';
DELETE FROM registrations    WHERE id = 'a0000000-0000-0000-0000-000000000010';
DELETE FROM users            WHERE id IN (
    'a0000000-0000-0000-0000-000000000001',
    'a0000000-0000-0000-0000-000000000002'
);

DELETE FROM flyway_schema_history WHERE script = 'V2__seed_users.sql';
