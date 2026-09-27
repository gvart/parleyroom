-- Clubs: a teacher's standing group of students (speech or reading club).
-- Individual club sessions stay open for self-join; group_id just ties a lesson to its club.

CREATE TYPE GROUP_TYPE AS ENUM ('SPEECH', 'READING');

CREATE TABLE study_groups
(
    id         UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    teacher_id UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name       VARCHAR(255) NOT NULL,
    level      LANGUAGE_LEVEL,
    type       GROUP_TYPE   NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_study_groups_teacher ON study_groups (teacher_id);

CREATE TRIGGER trg_study_groups_updated
    BEFORE UPDATE
    ON study_groups
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TABLE group_members
(
    group_id   UUID        NOT NULL REFERENCES study_groups (id) ON DELETE CASCADE,
    student_id UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    added_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (group_id, student_id)
);

CREATE INDEX idx_group_members_student ON group_members (student_id);

ALTER TABLE lessons
    ADD COLUMN group_id UUID REFERENCES study_groups (id) ON DELETE SET NULL;

CREATE INDEX idx_lessons_group ON lessons (group_id);
