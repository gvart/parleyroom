package com.gvart.parleyroom.email.service

import com.gvart.parleyroom.email.config.MailConfig
import com.gvart.parleyroom.email.data.EmailKind
import com.gvart.parleyroom.email.data.EmailLogTable
import com.gvart.parleyroom.email.template.EmailLanguage
import com.gvart.parleyroom.email.template.EmailTemplates
import com.gvart.parleyroom.email.template.LessonReminderVariant
import com.gvart.parleyroom.email.template.RenderedEmail
import com.gvart.parleyroom.email.template.RobbiPose
import com.gvart.parleyroom.email.template.ReminderFooter
import com.gvart.parleyroom.email.template.emailLanguageFor
import com.gvart.parleyroom.email.transfer.EmailCategory
import com.gvart.parleyroom.reminder.service.TeacherLesson
import com.gvart.parleyroom.reminder.service.TeacherLinks
import com.gvart.parleyroom.reminder.transfer.TeacherDigestLine
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
import java.net.URLEncoder
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

enum class SendResult {
    SENT,
    /** The provider failed; the email_log row stays success = false and a later call after [NotificationMailer.RETRY_FAILED_AFTER] retries it. */
    FAILED,
    /** This reminder (kind, ref, recipient, variant) was claimed before. */
    ALREADY_SENT,
    /** The recipient switched this category off. */
    OPTED_OUT,
    /** No such user, or the account is not active. */
    SKIPPED,
}

/**
 * The emails other modules send. Reminders check the recipient's preference, carry the unsubscribe
 * link + List-Unsubscribe headers, and go out at most once per (kind, ref, recipient, variant).
 */
class NotificationMailer(
    private val config: MailConfig,
    private val sender: EmailSender,
    private val templates: EmailTemplates,
    private val unsubscribeTokens: UnsubscribeTokens,
) {

    /** [inviteToken] is the plaintext registration token; the link opens the portal's register page. */
    suspend fun sendInvite(
        email: String,
        language: EmailLanguage,
        inviterName: String,
        role: UserRole,
        inviteToken: String,
        expiresAt: OffsetDateTime,
        registrationId: UUID? = null,
    ): Boolean {
        val registerUrl = "${config.appBaseUrl}/register?token=${encode(inviteToken)}&email=${encode(email)}"
        val rendered = templates.invite(language, inviterName, role, registerUrl, expiresAt)
        val sent = sender.send(outgoing(email, rendered))
        transaction {
            EmailLogTable.insert {
                it[recipientEmail] = email
                it[kind] = EmailKind.INVITE
                it[refId] = registrationId
                it[sentAt] = OffsetDateTime.now()
                it[success] = sent
            }
        }
        return sent
    }

    /**
     * [counterpartName]: the teacher for a student; the student(s) or club for a teacher; null to omit.
     * Deduplicated per [startsAt] too, so a rescheduled lesson is reminded again for its new time.
     */
    suspend fun sendLessonReminder(
        userId: UUID,
        lessonId: UUID,
        variant: LessonReminderVariant,
        lessonTitle: String,
        startsAt: OffsetDateTime,
        counterpartName: String?,
    ): SendResult = sendReminder(userId, EmailCategory.LESSON_REMINDERS, EmailKind.LESSON_REMINDER, lessonId, "${variant.name}@${startsAt.toEpochSecond()}") { user, footer ->
        val role = user[UserTable.role]
        templates.lessonReminder(
            emailLanguageFor(user), variant, lessonTitle, startsAt, zoneOf(user), role, counterpartName,
            lessonUrl = if (role == UserRole.STUDENT) "${config.appBaseUrl}/student/lessons" else "${config.appBaseUrl}/teacher/calendar",
            footer = footer,
        )
    }

    suspend fun sendHomeworkDueTomorrow(userId: UUID, homeworkId: UUID, assignmentTitle: String, dueAt: OffsetDateTime): SendResult =
        sendReminder(userId, EmailCategory.HOMEWORK_REMINDERS, EmailKind.HOMEWORK_DUE_TOMORROW, homeworkId, null) { user, footer ->
            templates.homeworkDueTomorrow(emailLanguageFor(user), assignmentTitle, dueAt, zoneOf(user), homeworkUrl(homeworkId), footer)
        }

    suspend fun sendHomeworkOverdue(userId: UUID, homeworkId: UUID, assignmentTitle: String, dueAt: OffsetDateTime): SendResult =
        sendReminder(userId, EmailCategory.HOMEWORK_REMINDERS, EmailKind.HOMEWORK_OVERDUE, homeworkId, null) { user, footer ->
            templates.homeworkOverdue(emailLanguageFor(user), assignmentTitle, dueAt, zoneOf(user), homeworkUrl(homeworkId), footer)
        }

    /** The [lesson]'s follow-up to its teacher, once per lesson; links the wrap-up flow. */
    suspend fun sendTeacherFollowUp(lesson: TeacherLesson, studentFirstName: String, wrapUp: Boolean, homework: Boolean): SendResult =
        sendReminder(lesson.teacherId, EmailCategory.TEACHER_REMINDERS, EmailKind.TEACHER_FOLLOW_UP, lesson.id, null) { user, footer ->
            templates.teacherFollowUp(
                emailLanguageFor(user), studentFirstName, lesson.title, lesson.startsAt, zoneOf(user), wrapUp, homework,
                TeacherLinks.wrapUp(config.appBaseUrl, lesson), footer,
            )
        }

    /** Once per lesson start, so a rescheduled lesson is reminded again; links the preparation assistant. */
    suspend fun sendTeacherPrep(lesson: TeacherLesson, studentFirstName: String): SendResult =
        sendReminder(lesson.teacherId, EmailCategory.TEACHER_REMINDERS, EmailKind.TEACHER_PREP, lesson.id, "@${lesson.startsAt.toEpochSecond()}") { user, footer ->
            templates.teacherPrep(emailLanguageFor(user), studentFirstName, lesson.title, lesson.startsAt, zoneOf(user), TeacherLinks.prep(config.appBaseUrl, lesson), footer)
        }

    /** Once per teacher and [week] (e.g. "2030-W11"). */
    suspend fun sendTeacherDigest(teacherId: UUID, week: String, students: List<TeacherDigestLine>): SendResult =
        sendReminder(teacherId, EmailCategory.TEACHER_REMINDERS, EmailKind.TEACHER_DIGEST, teacherId, week) { user, footer ->
            templates.teacherDigest(emailLanguageFor(user), students, TeacherLinks.dashboard(config.appBaseUrl), footer)
        }

    private suspend fun sendReminder(
        userId: UUID,
        category: EmailCategory,
        kind: EmailKind,
        refId: UUID,
        variant: String?,
        render: (ResultRow, ReminderFooter) -> RenderedEmail,
    ): SendResult {
        val user = transaction { UserTable.selectAll().where { UserTable.id eq userId }.singleOrNull() }
            ?: return SendResult.SKIPPED
        if (user[UserTable.status] != UserStatus.ACTIVE) return SendResult.SKIPPED
        val enabled = when (category) {
            EmailCategory.LESSON_REMINDERS -> user[UserTable.emailLessonReminders]
            EmailCategory.HOMEWORK_REMINDERS -> user[UserTable.emailHomeworkReminders]
            EmailCategory.VOCAB_REMINDERS -> user[UserTable.emailVocabReminders]
            // Toggled per type in teacher_reminder_settings, which the caller checked.
            EmailCategory.TEACHER_REMINDERS -> true
        }
        if (!enabled) return SendResult.OPTED_OUT

        val email = user[UserTable.email]
        val logId = transaction {
            EmailLogTable.insertIgnoreAndGetId {
                it[recipientUserId] = userId
                it[recipientEmail] = email
                it[this.kind] = kind
                it[this.refId] = refId
                it[this.variant] = variant
                it[sentAt] = OffsetDateTime.now()
                it[success] = false
            } ?: reclaimFailed(userId, kind, refId, variant)
        } ?: return SendResult.ALREADY_SENT

        val token = unsubscribeTokens.token(userId, category)
        val footer = ReminderFooter(
            settingsUrl = "${config.appBaseUrl}/${user[UserTable.role].name.lowercase()}/settings",
            unsubscribeUrl = unsubscribeTokens.portalUrl(token),
        )
        val sent = sender.send(outgoing(email, render(user, footer), unsubscribeTokens.headers(token)))
        transaction {
            EmailLogTable.update({ EmailLogTable.id eq logId }) {
                it[success] = sent
                it[sentAt] = OffsetDateTime.now()
            }
        }
        return if (sent) SendResult.SENT else SendResult.FAILED
    }

    /**
     * Takes over a claim whose last attempt failed at least [RETRY_FAILED_AFTER] ago; the age check keeps a
     * claim that is still being sent (also success = false) from going out twice. Null when there is none.
     */
    private fun reclaimFailed(userId: UUID, kind: EmailKind, refId: UUID, variant: String?) =
        EmailLogTable.updateReturning(listOf(EmailLogTable.id), where = {
            (EmailLogTable.kind eq kind) and (EmailLogTable.refId eq refId) and
                (EmailLogTable.recipientUserId eq userId) and
                (if (variant == null) EmailLogTable.variant.isNull() else EmailLogTable.variant eq variant) and
                (EmailLogTable.success eq false) and
                (EmailLogTable.sentAt less OffsetDateTime.now().minus(RETRY_FAILED_AFTER))
        }) {
            it[sentAt] = OffsetDateTime.now()
        }.singleOrNull()?.get(EmailLogTable.id)

    private fun outgoing(to: String, rendered: RenderedEmail, headers: Map<String, String> = emptyMap()) = OutgoingEmail(
        to = to,
        subject = rendered.subject,
        html = rendered.html,
        text = rendered.text,
        headers = headers,
        inlineImages = listOf(InlineImage(RobbiPose.CONTENT_ID, rendered.robbi.filename, "image/png", rendered.robbi.bytes)),
    )

    private fun homeworkUrl(homeworkId: UUID) = "${config.appBaseUrl}/student/homework/$homeworkId"

    private fun zoneOf(user: ResultRow): ZoneId =
        runCatching { ZoneId.of(user[UserTable.timezone]) }.getOrDefault(ZoneId.of("Europe/Berlin"))

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

    companion object {
        /** Well above the sender's request timeout. */
        val RETRY_FAILED_AFTER: Duration = Duration.ofMinutes(2)
    }
}
