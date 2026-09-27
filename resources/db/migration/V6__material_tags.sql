-- Material tagging by topic and grammar topic (level already exists on materials).

CREATE TABLE material_topics
(
    material_id UUID NOT NULL REFERENCES materials (id) ON DELETE CASCADE,
    topic_id    UUID NOT NULL REFERENCES topics (id) ON DELETE CASCADE,
    PRIMARY KEY (material_id, topic_id)
);

CREATE INDEX idx_material_topics_topic ON material_topics (topic_id);

CREATE TABLE material_grammar_topics
(
    material_id      UUID NOT NULL REFERENCES materials (id) ON DELETE CASCADE,
    grammar_topic_id UUID NOT NULL REFERENCES grammar_topics (id) ON DELETE CASCADE,
    PRIMARY KEY (material_id, grammar_topic_id)
);

CREATE INDEX idx_material_grammar_topics_grammar ON material_grammar_topics (grammar_topic_id);
