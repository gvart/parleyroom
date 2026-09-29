package com.gvart.parleyroom.vocabulary.data

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.data.pgEnum
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.topic.data.GrammarTopicTable
import com.gvart.parleyroom.topic.data.TopicTable
import com.gvart.parleyroom.user.data.UserTable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

enum class WordType { NOUN, VERB, ADJECTIVE, ADVERB, PREPOSITION, CONJUNCTION, PRONOUN, PHRASE, OTHER }
enum class NounArticle { DER, DIE, DAS }

object VocabEntryTable : UUIDTable("vocab_entries") {
    val teacherId = reference("teacher_id", UserTable)
    val lemma = varchar("lemma", 255)
    val article = pgEnum<NounArticle>("article", "NOUN_ARTICLE").nullable()
    val plural = varchar("plural", 255).nullable()
    val wordType = pgEnum<WordType>("word_type", "WORD_TYPE")
    val forms = text("forms").nullable()
    val government = text("government").nullable()
    val translations = jsonb<Map<String, String>>("translations", Json.Default)
    val explanationDe = text("explanation_de").nullable()
    val exampleSentence = text("example_sentence").nullable()
    val level = pgEnum<LanguageLevel>("level", "LANGUAGE_LEVEL").nullable()
    val synonyms = array<String>("synonyms", TextColumnType())
    val sourceLessonId = reference("source_lesson_id", LessonTable).nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
}

object VocabEntryTopicTable : Table("vocab_entry_topics") {
    val vocabEntryId = reference("vocab_entry_id", VocabEntryTable)
    val topicId = reference("topic_id", TopicTable)

    override val primaryKey = PrimaryKey(vocabEntryId, topicId)
}

object VocabEntryGrammarTopicTable : Table("vocab_entry_grammar_topics") {
    val vocabEntryId = reference("vocab_entry_id", VocabEntryTable)
    val grammarTopicId = reference("grammar_topic_id", GrammarTopicTable)

    override val primaryKey = PrimaryKey(vocabEntryId, grammarTopicId)
}

/** Words introduced in a lesson. */
object LessonVocabTable : Table("lesson_vocab") {
    val lessonId = reference("lesson_id", LessonTable)
    val vocabEntryId = reference("vocab_entry_id", VocabEntryTable)
    val orderIndex = integer("order_index").default(0)

    override val primaryKey = PrimaryKey(lessonId, vocabEntryId)
}
