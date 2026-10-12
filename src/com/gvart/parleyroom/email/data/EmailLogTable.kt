package com.gvart.parleyroom.email.data

import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

enum class EmailKind { INVITE, LESSON_REMINDER, HOMEWORK_DUE_TOMORROW, HOMEWORK_OVERDUE, TEACHER_FOLLOW_UP, TEACHER_PREP, TEACHER_DIGEST }

/** Every send attempt. Reminders claim their row first (unique on kind, ref_id, recipient, variant). */
object EmailLogTable : UUIDTable("email_log") {
    val recipientUserId = optReference("recipient_user_id", UserTable, onDelete = ReferenceOption.CASCADE)
    val recipientEmail = varchar("recipient_email", 255)
    val kind = enumerationByName<EmailKind>("kind", 64)
    val refId = javaUUID("ref_id").nullable()
    val variant = varchar("variant", 32).nullable()
    val sentAt = timestampWithTimeZone("sent_at")
    val success = bool("success")
}
