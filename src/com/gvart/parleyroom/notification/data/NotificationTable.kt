package com.gvart.parleyroom.notification.data

import com.gvart.parleyroom.common.data.pgEnum
import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

enum class NotificationType {
    LESSON_CREATED,
    LESSON_REQUESTED,
    LESSON_ACCEPTED,
    LESSON_CANCELLED,
    RESCHEDULE_REQUESTED,
    RESCHEDULE_ACCEPTED,
    RESCHEDULE_REJECTED,
    JOIN_REQUESTED,
    JOIN_ACCEPTED,
    JOIN_REJECTED,
    LESSON_STARTED,
    LESSON_COMPLETED,
    LESSON_MOVED,
    VOCAB_REVIEW_DUE,
    MATERIAL_SHARED,
    FOLDER_SHARED,
    MATERIAL_ATTACHED_TO_LESSON,
    HOMEWORK_ASSIGNED,
    HOMEWORK_SUBMITTED,
    HOMEWORK_REVIEWED,
    HOMEWORK_RETURNED,
    /** To the teacher: a student booked a 1:1 that was auto-confirmed. */
    LESSON_BOOKED,
    /** To the teacher: a student joined a club that auto-accepts joins. */
    CLUB_JOINED,
    /** To the student: the teacher marked a roadmap milestone done; reference = goal. Asks for a self-assessment. */
    MILESTONE_COMPLETED,
    /** To the teacher: the student self-assessed a done milestone with PARTLY / NOT_YET; reference = goal. */
    SELF_ASSESSMENT_MISMATCH,
    /** To the teacher, hours after a 1:1: wrap-up and/or homework missing (details); reference = lesson, actor = student. */
    LESSON_FOLLOW_UP_DUE,
    /** To the teacher before a 1:1 without a READY/DONE plan; reference = lesson, actor = student. */
    LESSON_PREP_DUE,
    /** To the teacher: students need attention (GET /api/v1/teacher/attention); actor = the teacher, details = ["students:<n>"]. */
    WEEKLY_DIGEST,
}

object NotificationTable : UUIDTable("notifications") {
    val userId = reference("user_id", UserTable)
    val actorId = reference("actor_id", UserTable)
    val type = pgEnum<NotificationType>("type", "NOTIFICATION_TYPE")
    val referenceId = javaUUID("reference_id").nullable()
    val viewed = bool("viewed").default(false)
    val createdAt = timestampWithTimeZone("created_at")
    val deliverAfter = timestampWithTimeZone("deliver_after").nullable()
    val oldScheduledAt = timestampWithTimeZone("old_scheduled_at").nullable()
    val newScheduledAt = timestampWithTimeZone("new_scheduled_at").nullable()
    val details = array<String>("details", TextColumnType()).nullable()
}
