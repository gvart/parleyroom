-- Adds a shared document surface to lesson_documents. This is the
-- teacher-authored, student-visible artifact of the lesson, distinct from
-- teacher_notes / student_notes which remain role-private scratchpads.
ALTER TABLE lesson_documents
    ADD COLUMN shared_document TEXT;
