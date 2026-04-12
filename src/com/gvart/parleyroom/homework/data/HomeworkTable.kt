package com.gvart.parleyroom.homework.data

import com.gvart.parleyroom.common.data.pgEnum
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.kotlin.datetime.date
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

enum class HomeworkCategory { WRITING, READING, GRAMMAR, VOCABULARY, LISTENING }
enum class HomeworkStatus { OPEN, SUBMITTED, IN_REVIEW, DONE, REJECTED }
enum class AttachmentType { FILE, LINK }

object HomeworkTable : UUIDTable("homework") {
    val lessonId = reference("lesson_id", LessonTable).nullable()
    val studentId = reference("student_id", UserTable)
    val teacherId = reference("teacher_id", UserTable)
    val title = varchar("title", 500)
    val description = text("description").nullable()
    val category = pgEnum<HomeworkCategory>("category", "HOMEWORK_CATEGORY")
    val dueDate = date("due_date").nullable()
    val status = pgEnum<HomeworkStatus>("status", "HOMEWORK_STATUS").default(HomeworkStatus.OPEN)
    val submissionText = text("submission_text").nullable()
    val submissionUrl = text("submission_url").nullable()
    val teacherFeedback = text("teacher_feedback").nullable()
    val attachmentType = pgEnum<AttachmentType>("attachment_type", "ATTACHMENT_TYPE").nullable()
    val attachmentUrl = text("attachment_url").nullable()
    val attachmentName = varchar("attachment_name", 255).nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
}
