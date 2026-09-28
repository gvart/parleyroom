-- One plain-text teacher note per lesson (lessons.raw_notes). The old lesson_documents
-- row (rich-text teacher notes, recap feedback, student notes/reflection) goes away.

-- 1. Keep the live teacher notes: HTML to plain text (one line per block), only where
--    raw_notes is still empty.
WITH converted AS (
    SELECT d.lesson_id,
           CASE
               WHEN d.teacher_notes ~* '<(p|div|li|br|h[1-6]|blockquote|ul|ol|tr|pre)(\s[^<>]*)?/?>' THEN
                   replace(replace(replace(replace(replace(replace(replace(
                       regexp_replace(
                           regexp_replace(d.teacher_notes,
                               '</?(p|div|li|br|h[1-6]|blockquote|ul|ol|tr|pre)(\s[^<>]*)?/?>', E'\n', 'gi'),
                           '</?[a-zA-Z][a-zA-Z0-9]*(\s[^<>]*)?/?>', '', 'g'),
                       '&nbsp;', ' '), '&lt;', '<'), '&gt;', '>'), '&quot;', '"'), '&#39;', ''''), '&apos;', ''''), '&amp;', '&')
               ELSE d.teacher_notes
           END AS notes
    FROM lesson_documents d
    WHERE d.teacher_notes IS NOT NULL
)
UPDATE lessons l
SET raw_notes = btrim(regexp_replace(c.notes, E'[ \\t]*\\n[ \\t\\n]*', E'\n', 'g'), E' \t\n')
FROM converted c
WHERE c.lesson_id = l.id
  AND (l.raw_notes IS NULL OR btrim(l.raw_notes) = '')
  AND btrim(c.notes, E' \t\n') <> '';

-- 2. Corrected sentences hang off the lesson directly.
ALTER TABLE lesson_corrections
    ADD COLUMN lesson_id UUID REFERENCES lessons (id) ON DELETE CASCADE;

UPDATE lesson_corrections c
SET lesson_id = d.lesson_id
FROM lesson_documents d
WHERE d.id = c.lesson_document_id;

ALTER TABLE lesson_corrections
    ALTER COLUMN lesson_id SET NOT NULL,
    DROP COLUMN lesson_document_id;

CREATE INDEX idx_lesson_corrections_lesson ON lesson_corrections (lesson_id);

-- 3. Drop the old document row and dead columns.
DROP TABLE lesson_documents;

ALTER TABLE lessons
    DROP COLUMN has_ai_summary;

ALTER TABLE users
    DROP COLUMN points;
