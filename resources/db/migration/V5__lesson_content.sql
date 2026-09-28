-- Lesson content for the post-lesson flow: raw notes, the prompt used, and tags.
-- Corrected sentences reuse lesson_corrections; words use lesson_vocab (V4).

ALTER TABLE lessons
    ADD COLUMN raw_notes   TEXT,
    ADD COLUMN prompt_used TEXT;

CREATE TABLE lesson_topics
(
    lesson_id UUID NOT NULL REFERENCES lessons (id) ON DELETE CASCADE,
    topic_id  UUID NOT NULL REFERENCES topics (id) ON DELETE CASCADE,
    PRIMARY KEY (lesson_id, topic_id)
);

CREATE INDEX idx_lesson_topics_topic ON lesson_topics (topic_id);

CREATE TABLE lesson_grammar_topics
(
    lesson_id        UUID NOT NULL REFERENCES lessons (id) ON DELETE CASCADE,
    grammar_topic_id UUID NOT NULL REFERENCES grammar_topics (id) ON DELETE CASCADE,
    PRIMARY KEY (lesson_id, grammar_topic_id)
);

CREATE INDEX idx_lesson_grammar_topics_grammar ON lesson_grammar_topics (grammar_topic_id);
