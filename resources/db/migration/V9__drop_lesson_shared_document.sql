-- The lesson's free-text shared document is replaced by block documents linked
-- through document_lessons (V8). Destructive: the DB reset for v2 is agreed.
ALTER TABLE lesson_documents
    DROP COLUMN shared_document;
