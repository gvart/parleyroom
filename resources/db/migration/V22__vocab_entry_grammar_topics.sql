-- The grammar a library word practises (e.g. "sich kümmern um" -> "Reflexive Verben").
CREATE TABLE vocab_entry_grammar_topics
(
    vocab_entry_id   UUID NOT NULL REFERENCES vocab_entries (id) ON DELETE CASCADE,
    grammar_topic_id UUID NOT NULL REFERENCES grammar_topics (id) ON DELETE CASCADE,
    PRIMARY KEY (vocab_entry_id, grammar_topic_id)
);

CREATE INDEX idx_vocab_entry_grammar_topics_grammar ON vocab_entry_grammar_topics (grammar_topic_id);
