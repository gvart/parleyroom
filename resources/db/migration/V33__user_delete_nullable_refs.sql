-- Hard-deleting a user: rows a student still uses (lessons, assignments, goals, materials, folders,
-- vocab entries, documents) survive a deleted teacher with the reference set to NULL, and actor-only references
-- (created/updated/cancelled by, event actor, shared/attached by) become NULL. The deleted
-- student's own homework and goals go with them.

ALTER TABLE lessons
    ALTER COLUMN teacher_id DROP NOT NULL,
    ALTER COLUMN created_by DROP NOT NULL,
    DROP CONSTRAINT lessons_teacher_id_fkey,
    DROP CONSTRAINT lessons_created_by_fkey,
    DROP CONSTRAINT lessons_updated_by_fkey,
    DROP CONSTRAINT lessons_cancelled_by_fkey,
    ADD CONSTRAINT lessons_teacher_id_fkey FOREIGN KEY (teacher_id) REFERENCES users (id) ON DELETE SET NULL,
    ADD CONSTRAINT lessons_created_by_fkey FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL,
    ADD CONSTRAINT lessons_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES users (id) ON DELETE SET NULL,
    ADD CONSTRAINT lessons_cancelled_by_fkey FOREIGN KEY (cancelled_by) REFERENCES users (id) ON DELETE SET NULL;

ALTER TABLE lesson_events
    ALTER COLUMN actor_id DROP NOT NULL,
    DROP CONSTRAINT lesson_events_actor_id_fkey,
    ADD CONSTRAINT lesson_events_actor_id_fkey FOREIGN KEY (actor_id) REFERENCES users (id) ON DELETE SET NULL;

ALTER TABLE assignments
    ALTER COLUMN teacher_id DROP NOT NULL,
    DROP CONSTRAINT assignments_teacher_id_fkey,
    ADD CONSTRAINT assignments_teacher_id_fkey FOREIGN KEY (teacher_id) REFERENCES users (id) ON DELETE SET NULL;

ALTER TABLE homework
    DROP CONSTRAINT homework_student_id_fkey,
    ADD CONSTRAINT homework_student_id_fkey FOREIGN KEY (student_id) REFERENCES users (id) ON DELETE CASCADE;

ALTER TABLE goals
    ALTER COLUMN teacher_id DROP NOT NULL,
    DROP CONSTRAINT goals_student_id_fkey,
    DROP CONSTRAINT goals_teacher_id_fkey,
    ADD CONSTRAINT goals_student_id_fkey FOREIGN KEY (student_id) REFERENCES users (id) ON DELETE CASCADE,
    ADD CONSTRAINT goals_teacher_id_fkey FOREIGN KEY (teacher_id) REFERENCES users (id) ON DELETE SET NULL;

ALTER TABLE materials
    ALTER COLUMN teacher_id DROP NOT NULL,
    DROP CONSTRAINT materials_teacher_id_fkey,
    ADD CONSTRAINT materials_teacher_id_fkey FOREIGN KEY (teacher_id) REFERENCES users (id) ON DELETE SET NULL;

ALTER TABLE material_folders
    ALTER COLUMN teacher_id DROP NOT NULL,
    DROP CONSTRAINT material_folders_teacher_id_fkey,
    ADD CONSTRAINT material_folders_teacher_id_fkey FOREIGN KEY (teacher_id) REFERENCES users (id) ON DELETE SET NULL;

ALTER TABLE material_shares
    ALTER COLUMN shared_by DROP NOT NULL,
    DROP CONSTRAINT material_shares_shared_by_fkey,
    ADD CONSTRAINT material_shares_shared_by_fkey FOREIGN KEY (shared_by) REFERENCES users (id) ON DELETE SET NULL;

ALTER TABLE folder_shares
    ALTER COLUMN shared_by DROP NOT NULL,
    DROP CONSTRAINT folder_shares_shared_by_fkey,
    ADD CONSTRAINT folder_shares_shared_by_fkey FOREIGN KEY (shared_by) REFERENCES users (id) ON DELETE SET NULL;

ALTER TABLE lesson_materials
    ALTER COLUMN attached_by DROP NOT NULL,
    DROP CONSTRAINT lesson_materials_attached_by_fkey,
    ADD CONSTRAINT lesson_materials_attached_by_fkey FOREIGN KEY (attached_by) REFERENCES users (id) ON DELETE SET NULL;

-- A deleted teacher's vocab entries and documents that students still hold stay readable.
ALTER TABLE vocab_entries
    ALTER COLUMN teacher_id DROP NOT NULL,
    DROP CONSTRAINT vocab_entries_teacher_id_fkey,
    ADD CONSTRAINT vocab_entries_teacher_id_fkey FOREIGN KEY (teacher_id) REFERENCES users (id) ON DELETE SET NULL;

ALTER TABLE documents
    ALTER COLUMN owner_id DROP NOT NULL,
    DROP CONSTRAINT documents_owner_id_fkey,
    ADD CONSTRAINT documents_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES users (id) ON DELETE SET NULL;
