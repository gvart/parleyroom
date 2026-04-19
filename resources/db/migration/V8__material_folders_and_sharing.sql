-- Materials v2: folders, explicit sharing, N:M lesson attachments, CEFR/skill tagging.

-- 1. New enums -----------------------------------------------------------------
CREATE TYPE MATERIAL_SKILL AS ENUM ('SPEAKING', 'LISTENING', 'READING', 'WRITING', 'GRAMMAR', 'VOCAB');

-- New notification types. (Postgres 12+ allows ADD VALUE inside a transaction;
-- the added values simply cannot be used in the same transaction, which is fine
-- because this migration does not reference them.)
ALTER TYPE NOTIFICATION_TYPE ADD VALUE IF NOT EXISTS 'MATERIAL_SHARED';
ALTER TYPE NOTIFICATION_TYPE ADD VALUE IF NOT EXISTS 'FOLDER_SHARED';
ALTER TYPE NOTIFICATION_TYPE ADD VALUE IF NOT EXISTS 'MATERIAL_ATTACHED_TO_LESSON';

-- 2. Folder tree ---------------------------------------------------------------
CREATE TABLE material_folders
(
    id               UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    parent_folder_id UUID                 REFERENCES material_folders (id) ON DELETE RESTRICT,
    teacher_id       UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name             VARCHAR(255) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_material_folders_teacher ON material_folders (teacher_id);
CREATE INDEX idx_material_folders_parent ON material_folders (parent_folder_id);

-- Unique name per (teacher, parent). Two partial indexes because NULL parent
-- needs a separate index (UNIQUE over NULLable columns treats NULLs as distinct).
CREATE UNIQUE INDEX uq_material_folders_name_in_parent
    ON material_folders (teacher_id, parent_folder_id, lower(name))
    WHERE parent_folder_id IS NOT NULL;

CREATE UNIQUE INDEX uq_material_folders_name_at_root
    ON material_folders (teacher_id, lower(name))
    WHERE parent_folder_id IS NULL;

CREATE TRIGGER trg_material_folders_updated
    BEFORE UPDATE
    ON material_folders
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- 3. Material metadata additions ----------------------------------------------
ALTER TABLE materials
    ADD COLUMN folder_id UUID REFERENCES material_folders (id) ON DELETE SET NULL,
    ADD COLUMN level     LANGUAGE_LEVEL,
    ADD COLUMN skill     MATERIAL_SKILL;

CREATE INDEX idx_materials_folder ON materials (folder_id);
CREATE INDEX idx_materials_level ON materials (level);
CREATE INDEX idx_materials_skill ON materials (skill);

-- 4. Direct per-material shares -----------------------------------------------
CREATE TABLE material_shares
(
    material_id UUID        NOT NULL REFERENCES materials (id) ON DELETE CASCADE,
    student_id  UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    shared_by   UUID        NOT NULL REFERENCES users (id),
    shared_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (material_id, student_id)
);

CREATE INDEX idx_material_shares_student ON material_shares (student_id);

-- 5. Folder shares (cascade to all contents, recursively) ---------------------
CREATE TABLE folder_shares
(
    folder_id  UUID        NOT NULL REFERENCES material_folders (id) ON DELETE CASCADE,
    student_id UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    shared_by  UUID        NOT NULL REFERENCES users (id),
    shared_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (folder_id, student_id)
);

CREATE INDEX idx_folder_shares_student ON folder_shares (student_id);

-- 6. Lesson N:M attachment (replaces materials.lesson_id) ---------------------
CREATE TABLE lesson_materials
(
    lesson_id   UUID        NOT NULL REFERENCES lessons (id) ON DELETE CASCADE,
    material_id UUID        NOT NULL REFERENCES materials (id) ON DELETE CASCADE,
    attached_by UUID        NOT NULL REFERENCES users (id),
    attached_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (lesson_id, material_id)
);

CREATE INDEX idx_lesson_materials_material ON lesson_materials (material_id);

-- 7. Migrate legacy 1:1 data into the new join/share tables -------------------
INSERT INTO lesson_materials (lesson_id, material_id, attached_by, attached_at)
SELECT m.lesson_id, m.id, m.teacher_id, m.created_at
FROM materials m
WHERE m.lesson_id IS NOT NULL
ON CONFLICT DO NOTHING;

INSERT INTO material_shares (material_id, student_id, shared_by, shared_at)
SELECT m.id, m.student_id, m.teacher_id, m.created_at
FROM materials m
WHERE m.student_id IS NOT NULL
ON CONFLICT DO NOTHING;

-- 8. Drop legacy columns now that the data is migrated -----------------------
DROP INDEX IF EXISTS idx_materials_lesson;
DROP INDEX IF EXISTS idx_materials_student;

ALTER TABLE materials
    DROP COLUMN lesson_id,
    DROP COLUMN student_id;
