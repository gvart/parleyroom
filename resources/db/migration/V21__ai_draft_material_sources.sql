-- Materials a student draft extracted words from: [{ id, name, kind, chars, truncated }] as sent to
-- the model (the truncation is only known after reading the files). Null = no material source.
ALTER TABLE ai_draft_bundles ADD COLUMN material_sources JSONB;
