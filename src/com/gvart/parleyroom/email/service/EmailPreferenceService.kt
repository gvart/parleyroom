package com.gvart.parleyroom.email.service

import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.email.transfer.EmailCategory
import com.gvart.parleyroom.reminder.service.TeacherReminderSettings
import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime

class EmailPreferenceService(private val tokens: UnsubscribeTokens) {

    /** Switches the token's category off. Idempotent; 400 UNSUBSCRIBE_TOKEN_INVALID for a bad token. */
    fun unsubscribe(token: String?): EmailCategory {
        val (userId, category) = token?.let(tokens::verify)
            ?: throw BadRequestException("Unsubscribe link is invalid", code = "UNSUBSCRIBE_TOKEN_INVALID")
        transaction {
            if (category == EmailCategory.TEACHER_REMINDERS) TeacherReminderSettings.disableEmail(userId)
            else UserTable.update({ UserTable.id eq userId }) {
                when (category) {
                    EmailCategory.LESSON_REMINDERS -> it[emailLessonReminders] = false
                    EmailCategory.HOMEWORK_REMINDERS -> it[emailHomeworkReminders] = false
                    EmailCategory.TEACHER_REMINDERS -> Unit
                }
                it[updatedAt] = OffsetDateTime.now()
            }
        }
        return category
    }
}
