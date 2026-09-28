-- Drops the pre-v2 vocabulary storage, superseded in V4 by vocab_entries,
-- student_vocab and lesson_vocab. Nothing in the application reads or writes
-- these tables any more.
DROP TABLE lesson_words;
DROP TABLE vocabulary_words;
DROP TYPE VOCAB_CATEGORY;
DROP TYPE VOCAB_STATUS;
