-- Grammar topics a student already covered before (e.g. from an onboarding draft): a floor of
-- COVERED for the derived progress status; lessons/homework and overrides still win above it.
CREATE TABLE student_grammar_coverage
(
    student_id       UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    grammar_topic_id UUID        NOT NULL REFERENCES grammar_topics (id) ON DELETE CASCADE,
    teacher_id       UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    bundle_id        UUID REFERENCES ai_draft_bundles (id) ON DELETE SET NULL,
    covered_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (student_id, grammar_topic_id)
);

CREATE INDEX idx_student_grammar_coverage_topic ON student_grammar_coverage (grammar_topic_id);
