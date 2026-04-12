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

-- Test registration tokens
INSERT INTO registrations (id, email, token, role, used, expires_at)
VALUES
    ('00000000-0000-0000-0000-000000000010', 'newuser@test.com', 'valid-registration-token', 'STUDENT', false, now() + interval '7 days'),
    ('00000000-0000-0000-0000-000000000011', 'expired@test.com', 'expired-registration-token', 'STUDENT', false, now() - interval '1 day'),
    ('00000000-0000-0000-0000-000000000012', 'used@test.com', 'used-registration-token', 'STUDENT', true, now() + interval '7 days');