package com.gvart.parleyroom.document.data

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.data.pgEnum
import com.gvart.parleyroom.group.data.GroupTable
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.topic.data.GrammarTopicTable
import com.gvart.parleyroom.topic.data.TopicTable
import com.gvart.parleyroom.user.data.UserTable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

enum class DocumentAudience { STUDENT, GROUP, LIBRARY }
enum class DocumentVersionReason { AUTOSAVE, SHARE, RESTORE, DUPLICATE }

object DocumentTable : UUIDTable("documents") {
    val ownerId = reference("owner_id", UserTable)
    val title = varchar("title", 255)
    val level = pgEnum<LanguageLevel>("level", "LANGUAGE_LEVEL").nullable()
    val audience = pgEnum<DocumentAudience>("audience", "DOCUMENT_AUDIENCE")
    val blocks = jsonb<JsonArray>("blocks", Json.Default)
    val createdFromLessonId = reference("created_from_lesson_id", LessonTable).nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
}

object DocumentTopicTable : Table("document_topics") {
    val documentId = reference("document_id", DocumentTable)
    val topicId = reference("topic_id", TopicTable)

    override val primaryKey = PrimaryKey(documentId, topicId)
}

object DocumentGrammarTopicTable : Table("document_grammar_topics") {
    val documentId = reference("document_id", DocumentTable)
    val grammarTopicId = reference("grammar_topic_id", GrammarTopicTable)

    override val primaryKey = PrimaryKey(documentId, grammarTopicId)
}

object DocumentStudentTable : Table("document_students") {
    val documentId = reference("document_id", DocumentTable)
    val studentId = reference("student_id", UserTable)
    val sharedAt = timestampWithTimeZone("shared_at")

    override val primaryKey = PrimaryKey(documentId, studentId)
}

object DocumentGroupTable : Table("document_groups") {
    val documentId = reference("document_id", DocumentTable)
    val groupId = reference("group_id", GroupTable)
    val sharedAt = timestampWithTimeZone("shared_at")

    override val primaryKey = PrimaryKey(documentId, groupId)
}

object DocumentLessonTable : Table("document_lessons") {
    val documentId = reference("document_id", DocumentTable)
    val lessonId = reference("lesson_id", LessonTable)
    val linkedAt = timestampWithTimeZone("linked_at")

    override val primaryKey = PrimaryKey(documentId, lessonId)
}

object DocumentVersionTable : UUIDTable("document_versions") {
    val documentId = reference("document_id", DocumentTable)
    val number = integer("number")
    val reason = pgEnum<DocumentVersionReason>("reason", "DOCUMENT_VERSION_REASON")
    val title = varchar("title", 255)
    val blocks = jsonb<JsonArray>("blocks", Json.Default)
    val createdBy = reference("created_by", UserTable).nullable()
    val createdAt = timestampWithTimeZone("created_at")
}
