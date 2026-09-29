package com.gvart.parleyroom.ai.data

import com.gvart.parleyroom.common.data.pgEnum
import com.gvart.parleyroom.document.data.DocumentTable
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.user.data.UserTable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

enum class DraftScope { LESSON, STUDENT }
enum class DraftMode { ONE_ON_ONE, CLUB }
enum class DraftStatus { DRAFT, SENT, DISCARDED }
enum class DraftItemKind { WORD, EXERCISE_DOCUMENT, TASK, NOTES_DOCUMENT }

/** Teacher-only AI output awaiting approval; nothing here is read by student endpoints. */
object DraftBundleTable : UUIDTable("ai_draft_bundles") {
    val teacherId = reference("teacher_id", UserTable)
    val scope = pgEnum<DraftScope>("scope", "AI_DRAFT_SCOPE")
    val lessonId = reference("lesson_id", LessonTable).nullable()
    val studentId = reference("student_id", UserTable).nullable()
    val mode = pgEnum<DraftMode>("mode", "AI_DRAFT_MODE")
    val status = pgEnum<DraftStatus>("status", "AI_DRAFT_STATUS").default(DraftStatus.DRAFT)
    val input = jsonb<JsonElement>("input", Json.Default)
    val sendResult = jsonb<JsonElement>("send_result", Json.Default).nullable()
    val sentAt = timestampWithTimeZone("sent_at").nullable()
    // Database clock only: the update trigger sets updated_at with now(), so inserts must too.
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestampWithTimeZone)
}

object DraftItemTable : UUIDTable("ai_draft_items") {
    val bundleId = reference("bundle_id", DraftBundleTable)
    val kind = pgEnum<DraftItemKind>("kind", "AI_DRAFT_ITEM_KIND")
    val position = integer("position")
    val approved = bool("approved").default(false)
    val payload = jsonb<JsonElement>("payload", Json.Default)
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestampWithTimeZone)
}

/** An unpublished AI revision of a document; students read documents.blocks until it is published. */
object DocumentDraftTable : Table("document_drafts") {
    val documentId = reference("document_id", DocumentTable)
    val title = varchar("title", 255)
    val blocks = jsonb<JsonArray>("blocks", Json.Default)
    val baseRevision = integer("base_revision")
    val jobId = reference("job_id", GenerationJobTable).nullable()
    val createdBy = reference("created_by", UserTable).nullable()
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestampWithTimeZone)

    override val primaryKey = PrimaryKey(documentId)
}
