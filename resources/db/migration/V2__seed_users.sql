-- Seed teacher and student for e2e testing
-- Password for both: 'password123'

-- Teacher
INSERT INTO users (id, email, first_name, last_name, password_hash, role, initials, level, created_at, updated_at)
VALUES ('a0000000-0000-0000-0000-000000000001', 'anya@parleyroom.com', 'Anya', 'Lehmann',
        '$2a$10$zikr3./cgtr3mqY8YhpbzOKijoUE4C2StC72a9L9swGo/KCSyhl/e', 'TEACHER', 'AL', NULL, now(), now());

-- Registration invite from teacher for student
INSERT INTO registrations (id, email, token, role, invited_by, used, expires_at)
VALUES ('a0000000-0000-0000-0000-000000000010', 'vlad@parleyroom.com', 'seed-student-token', 'STUDENT',
        'a0000000-0000-0000-0000-000000000001', TRUE, now() + interval '7 days');

-- Student
INSERT INTO users (id, email, first_name, last_name, password_hash, role, initials, level, created_at, updated_at)
VALUES ('a0000000-0000-0000-0000-000000000002', 'vlad@parleyroom.com', 'Vlad', 'Gladis',
        '$2a$10$zikr3./cgtr3mqY8YhpbzOKijoUE4C2StC72a9L9swGo/KCSyhl/e', 'STUDENT', 'VG', 'B1', now(), now());

-- Teacher-Student relationship
INSERT INTO teacher_students (id, teacher_id, student_id, lesson_types, status, started_at)
VALUES ('a0000000-0000-0000-0000-000000000020', 'a0000000-0000-0000-0000-000000000001',
        'a0000000-0000-0000-0000-000000000002', '{ONE_ON_ONE,SPEAKING_CLUB,READING_CLUB}', 'ACTIVE', now());