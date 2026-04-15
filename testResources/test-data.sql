-- Test users
-- password for all users is 'password123' (bcrypt hash)
INSERT INTO users (id, email, first_name, last_name, password_hash, role, initials, created_at, updated_at)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'admin@test.com', 'Test', 'Admin', '$2a$10$zikr3./cgtr3mqY8YhpbzOKijoUE4C2StC72a9L9swGo/KCSyhl/e', 'ADMIN', 'TA', now(), now()),
    ('00000000-0000-0000-0000-000000000002', 'teacher@test.com', 'Test', 'Teacher', '$2a$10$zikr3./cgtr3mqY8YhpbzOKijoUE4C2StC72a9L9swGo/KCSyhl/e', 'TEACHER', 'TT', now(), now()),
    ('00000000-0000-0000-0000-000000000003', 'student@test.com', 'Test', 'Student', '$2a$10$zikr3./cgtr3mqY8YhpbzOKijoUE4C2StC72a9L9swGo/KCSyhl/e', 'STUDENT', 'TS', now(), now()),
    ('00000000-0000-0000-0000-000000000004', 'student2@test.com', 'Test', 'Student2', '$2a$10$zikr3./cgtr3mqY8YhpbzOKijoUE4C2StC72a9L9swGo/KCSyhl/e', 'STUDENT', 'T2', now(), now());

-- Teacher-Student relationship
INSERT INTO teacher_students (id, teacher_id, student_id, lesson_types, status, started_at)
VALUES ('00000000-0000-0000-0000-000000000020', '00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000003', '{ONE_ON_ONE}', 'ACTIVE', now());

-- Test registration tokens (tokens are SHA-256 hashed)
-- plaintext: valid-registration-token -> 2cfbcb5651291dbc903c94466d3a6b11d859dfbb5674e9f3378e9b9930d27438
-- plaintext: expired-registration-token -> 7e0a45801bb201a7814effd80b6941eef531a26043bde55e068f12ad30236aa0
-- plaintext: used-registration-token -> 5b4114ad52a231abec8d16109578780aa5a291caa63ccf9d7a00038635cc3ed3
INSERT INTO registrations (id, email, token, role, used, expires_at, invited_by)
VALUES
    ('00000000-0000-0000-0000-000000000010', 'newuser@test.com', '2cfbcb5651291dbc903c94466d3a6b11d859dfbb5674e9f3378e9b9930d27438', 'STUDENT', false, now() + interval '7 days', '00000000-0000-0000-0000-000000000002'),
    ('00000000-0000-0000-0000-000000000011', 'expired@test.com', '7e0a45801bb201a7814effd80b6941eef531a26043bde55e068f12ad30236aa0', 'STUDENT', false, now() - interval '1 day', NULL),
    ('00000000-0000-0000-0000-000000000012', 'used@test.com', '5b4114ad52a231abec8d16109578780aa5a291caa63ccf9d7a00038635cc3ed3', 'STUDENT', true, now() + interval '7 days', NULL);