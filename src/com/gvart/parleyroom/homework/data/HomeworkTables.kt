package com.gvart.parleyroom.homework.data

import com.gvart.parleyroom.common.data.pgEnum
import com.gvart.parleyroom.document.data.DocumentTable
import com.gvart.parleyroom.group.data.GroupTable
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.material.data.MaterialTable
import com.gvart.parleyroom.user.data.UserTable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

enum class HomeworkStatus { OPEN, SUBMITTED, REVIEWED, DONE }
enum class HomeworkOutcome { REVIEWED, RETURNED, DONE }
enum class AssignmentItemKind { DOCUMENT, MATERIAL, TASK }
enum class HomeworkResponseType { TEXT, AUDIO, VIDEO, FILE }
enum class AutoResult { CORRECT, INCORRECT, PENDING_REVIEW, UNANSWERED }

object AssignmentTable : UUIDTable("assignments") {
    val teacherId = reference("teacher_id", UserTable)
    val lessonId = reference("lesson_id", LessonTable).nullable()
    val title = varchar("title", 255)
    val instructions = text("instructions").nullable()
    val dueDate = date("due_date").nullable()
    val itemCount = integer("item_count")
    val totalUnits = integer("total_units")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
}

object AssignmentGroupTable : Table("assignment_groups") {
    val assignmentId = reference("assignment_id", AssignmentTable)
    val groupId = reference("group_id", GroupTable)

    override val primaryKey = PrimaryKey(assignmentId, groupId)
}

object AssignmentItemTable : UUIDTable("assignment_items") {
    val assignmentId = reference("assignment_id", AssignmentTable)
    val position = integer("position")
    val kind = pgEnum<AssignmentItemKind>("kind", "ASSIGNMENT_ITEM_KIND")
    val title = varchar("title", 255)
    val task = text("task").nullable()
    val responseType = pgEnum<HomeworkResponseType>("response_type", "HOMEWORK_RESPONSE_TYPE").nullable()
    val documentId = reference("document_id", DocumentTable).nullable()
    val documentRevision = integer("document_revision").nullable()
    val blocks = jsonb<JsonArray>("blocks", Json.Default).nullable()
    val materialId = reference("material_id", MaterialTable).nullable()
}

object HomeworkTable : UUIDTable("homework") {
    val assignmentId = reference("assignment_id", AssignmentTable)
    val studentId = reference("student_id", UserTable)
    val status = pgEnum<HomeworkStatus>("status", "HOMEWORK_STATUS").default(HomeworkStatus.OPEN)
    val lastOutcome = pgEnum<HomeworkOutcome>("last_outcome", "HOMEWORK_OUTCOME").nullable()
    val attempt = integer("attempt").default(0)
    val feedback = text("feedback").nullable()
    val closedCorrect = integer("closed_correct").nullable()
    val closedTotal = integer("closed_total").nullable()
    val pendingReview = integer("pending_review").nullable()
    val unanswered = integer("unanswered").nullable()
    val lastSavedAt = timestampWithTimeZone("last_saved_at").nullable()
    val submittedAt = timestampWithTimeZone("submitted_at").nullable()
    val reviewedAt = timestampWithTimeZone("reviewed_at").nullable()
    val returnedAt = timestampWithTimeZone("returned_at").nullable()
    val doneAt = timestampWithTimeZone("done_at").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
}

object HomeworkAnswerTable : UUIDTable("homework_answers") {
    val homeworkId = reference("homework_id", HomeworkTable)
    val assignmentItemId = reference("assignment_item_id", AssignmentItemTable)
    val blockId = javaUUID("block_id").nullable()
    val itemRef = javaUUID("item_ref").nullable()
    val answer = jsonb<JsonObject>("answer", Json.Default).nullable()
    val answeredAt = timestampWithTimeZone("answered_at").nullable()
    val submittedAnswer = jsonb<JsonObject>("submitted_answer", Json.Default).nullable()
    val autoResult = pgEnum<AutoResult>("auto_result", "HOMEWORK_AUTO_RESULT").nullable()
    val autoScore = double("auto_score").nullable()
    val caseMismatch = bool("case_mismatch").default(false)
    val gapResults = jsonb<List<String>>("gap_results", Json.Default).nullable()
    val teacherCorrect = bool("teacher_correct").nullable()
    val comment = text("comment").nullable()
}

object HomeworkUploadTable : UUIDTable("homework_uploads") {
    val homeworkId = reference("homework_id", HomeworkTable)
    val assignmentItemId = reference("assignment_item_id", AssignmentItemTable)
    val storageKey = varchar("storage_key", 512)
    val fileName = varchar("file_name", 255)
    val contentType = varchar("content_type", 100)
    val size = long("size")
    val createdAt = timestampWithTimeZone("created_at")
}
