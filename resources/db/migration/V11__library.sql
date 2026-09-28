-- P5 Library: grammar checklist ordering and AI tag suggestions for materials.

-- Checklist order within (teacher, level); backfilled alphabetically.
ALTER TABLE grammar_topics
    ADD COLUMN position INT NOT NULL DEFAULT 0;

UPDATE grammar_topics g
SET position = ordered.rn - 1
FROM (SELECT id, row_number() OVER (PARTITION BY teacher_id, level ORDER BY lower(name)) AS rn
      FROM grammar_topics) ordered
WHERE g.id = ordered.id;

CREATE INDEX idx_grammar_topics_teacher_level_position ON grammar_topics (teacher_id, level, position);

ALTER TYPE GENERATION_JOB_KIND ADD VALUE 'SUGGEST_TAGS';

-- SUGGEST_TAGS: the material whose tags are suggested (its jobs go with it).
ALTER TABLE generation_jobs
    ADD COLUMN material_id UUID REFERENCES materials (id) ON DELETE CASCADE;

CREATE INDEX idx_generation_jobs_material ON generation_jobs (material_id, created_at DESC);
