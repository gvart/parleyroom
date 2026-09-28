package com.gvart.parleyroom.library

import com.gvart.parleyroom.ai.TEACHER
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.document.data.DocumentAudience
import com.gvart.parleyroom.document.data.DocumentGrammarTopicTable
import com.gvart.parleyroom.document.data.DocumentTable
import com.gvart.parleyroom.document.data.DocumentTopicTable
import com.gvart.parleyroom.lesson.data.LessonGrammarTopicTable
import com.gvart.parleyroom.lesson.data.LessonTopicTable
import com.gvart.parleyroom.material.data.MaterialGrammarTopicTable
import com.gvart.parleyroom.material.data.MaterialTable
import com.gvart.parleyroom.material.data.MaterialTopicTable
import com.gvart.parleyroom.material.data.MaterialType
import com.gvart.parleyroom.topic.data.GrammarTopicTable
import com.gvart.parleyroom.topic.data.TopicTable
import com.gvart.parleyroom.vocabulary.data.StudentVocabTable
import com.gvart.parleyroom.vocabulary.data.VocabEntryTable
import com.gvart.parleyroom.vocabulary.data.VocabEntryTopicTable
import com.gvart.parleyroom.vocabulary.data.WordType
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserTable
import kotlinx.serialization.json.JsonArray
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.OffsetDateTime
import java.util.UUID

/** Direct DB seeding of a teacher's library for library / merge tests. */
object LibraryFixtures {

    fun topic(name: String, parentId: UUID? = null, levels: List<LanguageLevel> = emptyList(), teacherId: UUID = TEACHER): UUID = transaction {
        val now = OffsetDateTime.now()
        TopicTable.insertAndGetId {
            it[TopicTable.teacherId] = teacherId
            it[TopicTable.parentId] = parentId
            it[TopicTable.name] = name
            it[TopicTable.levels] = levels.map(LanguageLevel::name)
            it[createdAt] = now
            it[updatedAt] = now
        }.value
    }

    fun grammar(
        name: String,
        level: LanguageLevel? = null,
        position: Int = 0,
        category: String? = null,
        explanation: String? = null,
        examples: List<String> = emptyList(),
        teacherId: UUID = TEACHER,
    ): UUID = transaction {
        val now = OffsetDateTime.now()
        GrammarTopicTable.insertAndGetId {
            it[GrammarTopicTable.teacherId] = teacherId
            it[GrammarTopicTable.name] = name
            it[GrammarTopicTable.level] = level
            it[GrammarTopicTable.position] = position
            it[GrammarTopicTable.category] = category
            it[GrammarTopicTable.explanation] = explanation
            it[GrammarTopicTable.examples] = examples
            it[createdAt] = now
            it[updatedAt] = now
        }.value
    }

    fun entry(lemma: String, topics: List<UUID> = emptyList(), level: LanguageLevel? = null, teacherId: UUID = TEACHER): UUID = transaction {
        val now = OffsetDateTime.now()
        val id = VocabEntryTable.insertAndGetId {
            it[VocabEntryTable.teacherId] = teacherId
            it[VocabEntryTable.lemma] = lemma
            it[wordType] = WordType.PHRASE
            it[translations] = emptyMap()
            it[synonyms] = emptyList()
            it[VocabEntryTable.level] = level
            it[createdAt] = now
            it[updatedAt] = now
        }.value
        topics.forEach { topicId ->
            VocabEntryTopicTable.insert { it[vocabEntryId] = id; it[VocabEntryTopicTable.topicId] = topicId }
        }
        id
    }

    fun assign(studentId: UUID, entryId: UUID, addedAt: OffsetDateTime = OffsetDateTime.now()) = transaction {
        StudentVocabTable.insert {
            it[StudentVocabTable.studentId] = studentId
            it[vocabEntryId] = entryId
            it[StudentVocabTable.addedAt] = addedAt
        }
    }

    fun document(
        title: String,
        topics: List<UUID> = emptyList(),
        grammar: List<UUID> = emptyList(),
        level: LanguageLevel? = null,
        blocks: JsonArray = JsonArray(emptyList()),
        teacherId: UUID = TEACHER,
    ): UUID = transaction {
        val now = OffsetDateTime.now()
        val id = DocumentTable.insertAndGetId {
            it[ownerId] = teacherId
            it[DocumentTable.title] = title
            it[DocumentTable.level] = level
            it[audience] = DocumentAudience.LIBRARY
            it[DocumentTable.blocks] = blocks
            it[createdAt] = now
            it[updatedAt] = now
        }.value
        topics.forEach { topicId -> DocumentTopicTable.insert { it[documentId] = id; it[DocumentTopicTable.topicId] = topicId } }
        grammar.forEach { g -> DocumentGrammarTopicTable.insert { it[documentId] = id; it[grammarTopicId] = g } }
        id
    }

    fun material(
        name: String,
        topics: List<UUID> = emptyList(),
        grammar: List<UUID> = emptyList(),
        level: LanguageLevel? = null,
        teacherId: UUID = TEACHER,
    ): UUID = transaction {
        val id = MaterialTable.insertAndGetId {
            it[MaterialTable.teacherId] = teacherId
            it[MaterialTable.name] = name
            it[type] = MaterialType.LINK
            it[url] = "https://example.com/$name"
            it[MaterialTable.level] = level
            it[createdAt] = OffsetDateTime.now()
        }.value
        topics.forEach { topicId -> MaterialTopicTable.insert { it[materialId] = id; it[MaterialTopicTable.topicId] = topicId } }
        grammar.forEach { g -> MaterialGrammarTopicTable.insert { it[materialId] = id; it[grammarTopicId] = g } }
        id
    }

    /** A second teacher (login teacher2@test.com / the test password). Call after the app booted. */
    fun otherTeacher(): UUID = transaction {
        val passwordHash = UserTable.selectAll().where { UserTable.id eq TEACHER }.single()[UserTable.passwordHash]
        UserTable.insertAndGetId {
            it[email] = "teacher2@test.com"
            it[firstName] = "Other"
            it[lastName] = "Teacher"
            it[role] = UserRole.TEACHER
            it[UserTable.passwordHash] = passwordHash
            it[initials] = "OT"
            it[createdAt] = OffsetDateTime.now()
            it[updatedAt] = OffsetDateTime.now()
        }.value
    }

    fun tagLesson(lessonId: UUID, topics: List<UUID> = emptyList(), grammar: List<UUID> = emptyList()) = transaction {
        topics.forEach { topicId -> LessonTopicTable.insert { it[LessonTopicTable.lessonId] = lessonId; it[LessonTopicTable.topicId] = topicId } }
        grammar.forEach { g -> LessonGrammarTopicTable.insert { it[LessonGrammarTopicTable.lessonId] = lessonId; it[grammarTopicId] = g } }
    }
}
