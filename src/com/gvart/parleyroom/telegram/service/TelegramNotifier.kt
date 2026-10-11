package com.gvart.parleyroom.telegram.service

import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.email.service.SendResult
import com.gvart.parleyroom.email.template.LessonReminderVariant
import com.gvart.parleyroom.email.template.emailLanguageFor
import com.gvart.parleyroom.email.transfer.EmailCategory
import com.gvart.parleyroom.telegram.config.TelegramNotificationConfig
import com.gvart.parleyroom.telegram.data.TelegramKind
import com.gvart.parleyroom.telegram.data.TelegramLogTable
import com.gvart.parleyroom.telegram.template.TelegramContent
import com.gvart.parleyroom.telegram.template.TelegramTemplates
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserStatus
import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnoreAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.updateReturning
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * Telegram counterpart of NotificationMailer for reminders: sent to users with a linked telegram_id who have the
 * category on and are not marked unreachable, at most once per (kind, ref, recipient, variant). A 403 from Telegram
 * marks the user unreachable (telegram_unreachable_at) until they re-link or a test message succeeds.
 */
class TelegramNotifier(
    private val config: TelegramNotificationConfig,
    private val sender: TelegramSender,
    private val templates: TelegramTemplates,
) {

    /** Sends regardless of toggles and the unreachable flag; success clears the flag, 403 sets it. */
    suspend fun sendTest(userId: UUID): TelegramSendResult {
        val user = transaction { UserTable.selectAll().where { UserTable.id eq userId }.singleOrNull() }
            ?: throw NotFoundException("User not found", code = "USER_NOT_FOUND")
        val chatId = user[UserTable.telegramId]
            ?: throw BadRequestException("No Telegram account is linked", code = "TELEGRAM_NOT_LINKED")
        val content = templates.test(emailLanguageFor(user))
        val result = sender.send(message(chatId, content, appUrl(user, "")))
        transaction {
            TelegramLogTable.insert {
                it[recipientUserId] = userId
                it[this.chatId] = chatId
                it[kind] = TelegramKind.TEST
                it[sentAt] = OffsetDateTime.now()
                it[success] = result == TelegramSendResult.SENT
            }
            if (result == TelegramSendResult.SENT) {
                UserTable.update({ UserTable.id eq userId }) { it[telegramUnreachableAt] = null }
            }
        }
        if (result == TelegramSendResult.FORBIDDEN) markUnreachable(userId, chatId)
        return result
    }

    /** Deduplicated per [startsAt] too, so a rescheduled lesson is reminded again for its new time. */
    suspend fun sendLessonReminder(
        userId: UUID,
        lessonId: UUID,
        variant: LessonReminderVariant,
        lessonTitle: String,
        startsAt: OffsetDateTime,
        counterpartName: String?,
    ): SendResult = sendReminder(userId, EmailCategory.LESSON_REMINDERS, TelegramKind.LESSON_REMINDER, lessonId, "${variant.name}@${startsAt.toEpochSecond()}") { user ->
        val role = user[UserTable.role]
        templates.lessonReminder(emailLanguageFor(user), variant, lessonTitle, startsAt, zoneOf(user), role, counterpartName) to
            appUrl(user, if (role == UserRole.STUDENT) "/lessons" else "/calendar")
    }

    suspend fun sendHomeworkDueTomorrow(userId: UUID, homeworkId: UUID, assignmentTitle: String): SendResult =
        sendReminder(userId, EmailCategory.HOMEWORK_REMINDERS, TelegramKind.HOMEWORK_DUE_TOMORROW, homeworkId, null) { user ->
            templates.homeworkDueTomorrow(emailLanguageFor(user), assignmentTitle) to appUrl(user, "/homework/$homeworkId")
        }

    suspend fun sendHomeworkOverdue(userId: UUID, homeworkId: UUID, assignmentTitle: String): SendResult =
        sendReminder(userId, EmailCategory.HOMEWORK_REMINDERS, TelegramKind.HOMEWORK_OVERDUE, homeworkId, null) { user ->
            templates.homeworkOverdue(emailLanguageFor(user), assignmentTitle) to appUrl(user, "/homework/$homeworkId")
        }

    private suspend fun sendReminder(
        userId: UUID,
        category: EmailCategory,
        kind: TelegramKind,
        refId: UUID,
        variant: String?,
        render: (ResultRow) -> Pair<TelegramContent, String>,
    ): SendResult {
        val user = transaction { UserTable.selectAll().where { UserTable.id eq userId }.singleOrNull() }
            ?: return SendResult.SKIPPED
        if (user[UserTable.status] != UserStatus.ACTIVE) return SendResult.SKIPPED
        val enabled = when (category) {
            EmailCategory.LESSON_REMINDERS -> user[UserTable.telegramLessonReminders]
            EmailCategory.HOMEWORK_REMINDERS -> user[UserTable.telegramHomeworkReminders]
        }
        if (!enabled) return SendResult.OPTED_OUT
        val chatId = user[UserTable.telegramId] ?: return SendResult.SKIPPED
        if (user[UserTable.telegramUnreachableAt] != null) return SendResult.SKIPPED

        val logId = transaction {
            TelegramLogTable.insertIgnoreAndGetId {
                it[recipientUserId] = userId
                it[this.chatId] = chatId
                it[this.kind] = kind
                it[this.refId] = refId
                it[this.variant] = variant
                it[sentAt] = OffsetDateTime.now()
                it[success] = false
            } ?: reclaimFailed(userId, kind, refId, variant)
        } ?: return SendResult.ALREADY_SENT

        val (content, url) = render(user)
        val result = sender.send(message(chatId, content, url))
        transaction {
            TelegramLogTable.update({ TelegramLogTable.id eq logId }) {
                it[success] = result == TelegramSendResult.SENT
                it[sentAt] = OffsetDateTime.now()
            }
        }
        if (result == TelegramSendResult.FORBIDDEN) markUnreachable(userId, chatId)
        return if (result == TelegramSendResult.SENT) SendResult.SENT else SendResult.FAILED
    }

    /** Only while [chatId] is still the linked account: a re-link in the meantime starts clean. */
    private fun markUnreachable(userId: UUID, chatId: Long) = transaction {
        UserTable.update({ (UserTable.id eq userId) and (UserTable.telegramId eq chatId) }) {
            it[telegramUnreachableAt] = OffsetDateTime.now()
        }
    }

    /** See NotificationMailer.reclaimFailed. */
    private fun reclaimFailed(userId: UUID, kind: TelegramKind, refId: UUID, variant: String?) =
        TelegramLogTable.updateReturning(listOf(TelegramLogTable.id), where = {
            (TelegramLogTable.kind eq kind) and (TelegramLogTable.refId eq refId) and
                (TelegramLogTable.recipientUserId eq userId) and
                (if (variant == null) TelegramLogTable.variant.isNull() else TelegramLogTable.variant eq variant) and
                (TelegramLogTable.success eq false) and
                (TelegramLogTable.sentAt less OffsetDateTime.now().minus(RETRY_FAILED_AFTER))
        }) {
            it[sentAt] = OffsetDateTime.now()
        }.singleOrNull()?.get(TelegramLogTable.id)

    private fun message(chatId: Long, content: TelegramContent, url: String) =
        OutgoingTelegramMessage(chatId, content.text, TelegramButton(content.buttonLabel, url, webApp = config.miniAppBaseUrl.isNotEmpty()))

    /** [path] in the Mini App, or the matching portal page (under /student or /teacher) without one. */
    private fun appUrl(user: ResultRow, path: String): String =
        if (config.miniAppBaseUrl.isNotEmpty()) "${config.miniAppBaseUrl}$path"
        else {
            val area = if (user[UserTable.role] == UserRole.STUDENT) "student" else "teacher"
            if (path.isEmpty()) config.appBaseUrl else "${config.appBaseUrl}/$area$path"
        }

    private fun zoneOf(user: ResultRow): ZoneId =
        runCatching { ZoneId.of(user[UserTable.timezone]) }.getOrDefault(ZoneId.of("Europe/Berlin"))

    companion object {
        /** Well above the sender's request timeout. */
        val RETRY_FAILED_AFTER: Duration = Duration.ofMinutes(2)
    }
}
