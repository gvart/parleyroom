package com.gvart.parleyroom.reminder.service

import com.gvart.parleyroom.admin.data.FeatureFlag
import com.gvart.parleyroom.admin.service.FeatureFlagService
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.email.service.NotificationMailer
import com.gvart.parleyroom.email.service.SendResult
import com.gvart.parleyroom.goal.data.GoalStatus
import com.gvart.parleyroom.goal.data.GoalTable
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.lesson.data.LessonStudentStatus
import com.gvart.parleyroom.lesson.data.LessonStudentTable
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.lessonprep.data.LessonPlanStatus
import com.gvart.parleyroom.lessonprep.data.LessonPlanTable
import com.gvart.parleyroom.notification.data.NotificationType
import com.gvart.parleyroom.notification.service.NotificationService
import com.gvart.parleyroom.reminder.data.BeforeLessonMode
import com.gvart.parleyroom.reminder.data.TeacherReminderKind
import com.gvart.parleyroom.reminder.data.TeacherReminderLogTable
import com.gvart.parleyroom.reminder.transfer.AttentionReasonType
import com.gvart.parleyroom.reminder.transfer.TeacherDigestLine
import com.gvart.parleyroom.reminder.transfer.TeacherReminderSettingsRequest
import com.gvart.parleyroom.reminder.transfer.TeacherReminderSettingsRequest.Companion.parseTime
import com.gvart.parleyroom.telegram.service.TelegramNotifier
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserStatus
import com.gvart.parleyroom.user.data.UserTable
import kotlinx.coroutines.CancellationException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.insertIgnoreAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.DayOfWeek
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/**
 * Teacher reminders (feature flag `teacherReminders`), run by the reminder job next to [ReminderService]:
 *  - after a 1:1 lesson ([TeacherReminderSettingsRequest.afterLesson]): once its end + hoursAfter has passed, if it is
 *    not wrapped up and/or has no homework (lessons that ended more than [FOLLOW_UP_MAX_AGE] ago are skipped);
 *  - before a CONFIRMED 1:1 without a READY / DONE plan (needs `lessonPrep`): the day before at a time, or hours before;
 *  - the weekly digest: [TeacherAttentionService]'s list at the teacher's weekday and time, skipped when empty.
 * Times are read in the teacher's zone. In-app always; email and Telegram per type. Every reminder is claimed per
 * channel first (teacher_reminder_log, email_log, telegram_log), so repeated runs and several pods send it once.
 */
class TeacherReminderService(
    private val flags: FeatureFlagService,
    private val attention: TeacherAttentionService,
    private val notifications: NotificationService,
    private val mailer: NotificationMailer,
    private val telegram: TelegramNotifier,
) {

    private val log = LoggerFactory.getLogger(TeacherReminderService::class.java)

    private data class Teacher(val zone: ZoneId, val settings: TeacherReminderSettingsRequest)

    private data class FollowUp(val lesson: TeacherLesson, val studentFirstName: String, val wrapUp: Boolean, val homework: Boolean, val teacher: Teacher)

    private data class Prep(val lesson: TeacherLesson, val studentFirstName: String, val teacher: Teacher)

    private data class Digest(val teacherId: UUID, val week: String, val lines: List<TeacherDigestLine>, val teacher: Teacher)

    suspend fun runOnce(now: OffsetDateTime = OffsetDateTime.now()) {
        val enabled = flags.flags()
        if (enabled[FeatureFlag.TEACHER_REMINDERS.key] != true) return
        val features = attention.features(enabled)
        val results = mutableMapOf<SendResult, Int>()
        /** An error on one channel counts as FAILED and never keeps the other channels (or the next reminder) from sending. */
        suspend fun send(block: suspend () -> SendResult) {
            val result = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Teacher reminder send failed", e)
                SendResult.FAILED
            }
            results.merge(result, 1, Int::plus)
        }

        for (due in followUps(now, features)) {
            val lesson = due.lesson
            val settings = due.teacher.settings.afterLesson
            send {
                inApp(lesson.teacherId, TeacherReminderKind.AFTER_LESSON, lesson.id, "", lesson.studentId, NotificationType.LESSON_FOLLOW_UP_DUE, lesson.id,
                    listOfNotNull(WRAP_UP.takeIf { due.wrapUp }, HOMEWORK.takeIf { due.homework }))
            }
            if (settings.email) send { mailer.sendTeacherFollowUp(lesson, due.studentFirstName, due.wrapUp, due.homework) }
            if (settings.telegram) send { telegram.sendTeacherFollowUp(lesson, due.studentFirstName, due.wrapUp, due.homework) }
        }

        for (due in preps(now, features)) {
            val lesson = due.lesson
            val settings = due.teacher.settings.beforeLesson
            val variant = "@${lesson.startsAt.toEpochSecond()}"
            send { inApp(lesson.teacherId, TeacherReminderKind.BEFORE_LESSON, lesson.id, variant, lesson.studentId, NotificationType.LESSON_PREP_DUE, lesson.id, null) }
            if (settings.email) send { mailer.sendTeacherPrep(lesson, due.studentFirstName) }
            if (settings.telegram) send { telegram.sendTeacherPrep(lesson, due.studentFirstName) }
        }

        for (due in digests(now, features)) {
            val settings = due.teacher.settings.weeklyDigest
            send {
                inApp(due.teacherId, TeacherReminderKind.WEEKLY_DIGEST, due.teacherId, due.week, due.teacherId, NotificationType.WEEKLY_DIGEST, null,
                    listOf("students:${due.lines.size}"))
            }
            if (settings.email) send { mailer.sendTeacherDigest(due.teacherId, due.week, due.lines) }
            if (settings.telegram) send { telegram.sendTeacherDigest(due.teacherId, due.week, due.lines) }
        }

        val sent = results[SendResult.SENT] ?: 0
        val failed = results[SendResult.FAILED] ?: 0
        if (sent + failed > 0) log.info("Teacher reminders: $sent sent, $failed failed")
    }

    /** Claims the in-app reminder and creates its notification in one transaction; ALREADY_SENT when claimed before. */
    private fun inApp(
        teacherId: UUID,
        kind: TeacherReminderKind,
        refId: UUID,
        variant: String,
        actorId: UUID,
        type: NotificationType,
        referenceId: UUID?,
        details: List<String>?,
    ): SendResult = transaction {
        TeacherReminderLogTable.insertIgnoreAndGetId {
            it[TeacherReminderLogTable.teacherId] = teacherId
            it[TeacherReminderLogTable.kind] = kind
            it[TeacherReminderLogTable.refId] = refId
            it[TeacherReminderLogTable.variant] = variant
            it[sentAt] = OffsetDateTime.now()
        } ?: return@transaction SendResult.ALREADY_SENT
        notifications.createNotification(teacherId, actorId, type, referenceId, details = details)
        SendResult.SENT
    }

    private fun followUps(now: OffsetDateTime, features: AttentionFeatures): List<FollowUp> = transaction {
        val ended = TeacherLessons.ended(null, null, now.minus(FOLLOW_UP_MAX_AGE), now)
        val teachers = teachers(ended.map { it.teacherId })
        val names = firstNames(ended.map { it.studentId })
        val due = ended.filter { lesson ->
            val settings = teachers[lesson.teacherId]?.settings?.afterLesson ?: return@filter false
            settings.enabled && lesson.studentId in names && !lesson.endsAt.plusHours(settings.hoursAfter.toLong()).isAfter(now)
        }
        val withGoal = if (features.lessonPrep || !features.roadmap) emptySet() else activeGoalStudents(due.map { it.studentId })
        val open = if (!features.wrapUp) emptySet() else TeacherLessons.notWrappedUp(due).map { it.id }.toSet()
        val withHomework = TeacherLessons.withHomework(due.map { it.id })
        due.mapNotNull { lesson ->
            val wrapUp = lesson.id in open && (features.lessonPrep || lesson.studentId in withGoal)
            val homework = lesson.id !in withHomework
            if (!wrapUp && !homework) null
            else FollowUp(lesson, names.getValue(lesson.studentId), wrapUp, homework, teachers.getValue(lesson.teacherId))
        }
    }

    private fun preps(now: OffsetDateTime, features: AttentionFeatures): List<Prep> {
        if (!features.lessonPrep) return emptyList()
        return transaction {
            val upcoming = (LessonTable innerJoin LessonStudentTable).select(TeacherLessons.LESSON_COLUMNS)
                .where {
                    (LessonTable.type eq LessonType.ONE_ON_ONE) and (LessonTable.status eq LessonStatus.CONFIRMED) and
                        (LessonStudentTable.status eq LessonStudentStatus.CONFIRMED) and
                        (LessonTable.scheduledAt greater now) and (LessonTable.scheduledAt lessEq now.plus(PREP_LOOKAHEAD))
                }
                .filter { it[LessonTable.teacherId] != null }
                .map(TeacherLessons::toLesson)
            val teachers = teachers(upcoming.map { it.teacherId })
            val names = firstNames(upcoming.map { it.studentId })
            val due = upcoming.filter { lesson ->
                val teacher = teachers[lesson.teacherId] ?: return@filter false
                teacher.settings.beforeLesson.enabled && lesson.studentId in names && !prepAt(lesson, teacher).isAfter(now)
            }
            val prepared = if (due.isEmpty()) emptySet() else LessonPlanTable.select(LessonPlanTable.lessonId)
                .where {
                    (LessonPlanTable.lessonId inList due.map { it.id }) and
                        (LessonPlanTable.status inList listOf(LessonPlanStatus.READY, LessonPlanStatus.DONE))
                }
                .mapNotNull { it[LessonPlanTable.lessonId]?.value }.toSet()
            due.filter { it.id !in prepared }.map { Prep(it, names.getValue(it.studentId), teachers.getValue(it.teacherId)) }
        }
    }

    /** When the before-lesson reminder of [lesson] is due, in the teacher's zone (DST-safe). */
    private fun prepAt(lesson: TeacherLesson, teacher: Teacher): OffsetDateTime {
        val settings = teacher.settings.beforeLesson
        return when (BeforeLessonMode.valueOf(settings.mode)) {
            BeforeLessonMode.HOURS_BEFORE -> lesson.startsAt.minusHours(settings.hoursBefore.toLong())
            BeforeLessonMode.DAY_BEFORE -> lesson.startsAt.atZoneSameInstant(teacher.zone).toLocalDate().minusDays(1)
                .atTime(parseTime(settings.time)!!).atZone(teacher.zone).toOffsetDateTime()
        }
    }

    private fun digests(now: OffsetDateTime, features: AttentionFeatures): List<Digest> = transaction {
        val ids = UserTable.select(UserTable.id)
            .where { (UserTable.role neq UserRole.STUDENT) and (UserTable.status eq UserStatus.ACTIVE) }
            .map { it[UserTable.id].value }
        teachers(ids).mapNotNull { (teacherId, teacher) ->
            val settings = teacher.settings.weeklyDigest
            if (!settings.enabled) return@mapNotNull null
            // Instants, not OffsetDateTime.compareTo (which also orders equal instants by their local time).
            val scheduled = digestAt(now, teacher)
            val sinceScheduled = Duration.between(scheduled.toInstant(), now.toInstant())
            if (sinceScheduled.isNegative || sinceScheduled >= DIGEST_WINDOW) return@mapNotNull null
            val students = attention.compute(teacherId, now, features)
            if (students.isEmpty()) return@mapNotNull null
            val names = firstNames(students.map { UUID.fromString(it.studentId) })
            val lines = students.map { student ->
                TeacherDigestLine(
                    names[UUID.fromString(student.studentId)] ?: student.studentName,
                    student.reasons.groupingBy { it.type }.eachCount().toSortedMap(compareBy(AttentionReasonType::ordinal)),
                )
            }
            val week = "${scheduled.get(IsoFields.WEEK_BASED_YEAR)}-W%02d".format(scheduled.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
            Digest(teacherId, week, lines, teacher)
        }
    }

    /** This week's digest moment: the latest digest weekday at the digest time, not after today in the teacher's zone. */
    private fun digestAt(now: OffsetDateTime, teacher: Teacher): ZonedDateTime {
        val settings = teacher.settings.weeklyDigest
        return now.atZoneSameInstant(teacher.zone).toLocalDate()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.valueOf(settings.weekday)))
            .atTime(parseTime(settings.time)!!).atZone(teacher.zone)
    }

    /** Active teachers among [ids] with their zone and settings. */
    private fun teachers(ids: List<UUID>): Map<UUID, Teacher> {
        if (ids.isEmpty()) return emptyMap()
        val zones = UserTable.select(UserTable.id, UserTable.timezone)
            .where { (UserTable.id inList ids.distinct()) and (UserTable.status eq UserStatus.ACTIVE) }
            .associate { it[UserTable.id].value to (runCatching { ZoneId.of(it[UserTable.timezone]) }.getOrDefault(DEFAULT_ZONE)) }
        val settings = TeacherReminderSettings.of(zones.keys)
        return zones.mapValues { (id, zone) -> Teacher(zone, settings.getValue(id)) }
    }

    /** First names of the active users among [ids]; notifications and messages name students by first name only. */
    private fun firstNames(ids: List<UUID>): Map<UUID, String> =
        if (ids.isEmpty()) emptyMap()
        else UserTable.select(UserTable.id, UserTable.firstName)
            .where { (UserTable.id inList ids.distinct()) and (UserTable.status eq UserStatus.ACTIVE) }
            .associate { it[UserTable.id].value to it[UserTable.firstName] }

    private fun activeGoalStudents(ids: List<UUID>): Set<UUID> =
        if (ids.isEmpty()) emptySet()
        else GoalTable.select(GoalTable.studentId)
            .where { (GoalTable.studentId inList ids.distinct()) and (GoalTable.status eq GoalStatus.ACTIVE) }
            .map { it[GoalTable.studentId].value }.toSet()

    companion object {
        /** Lessons that ended longer ago get no follow-up (no backlog flood when the flag is first turned on). */
        val FOLLOW_UP_MAX_AGE: Duration = Duration.ofDays(7)

        /** Covers DAY_BEFORE (up to two days ahead) and HOURS_BEFORE (up to 72 h). */
        val PREP_LOOKAHEAD: Duration = Duration.ofHours(73)

        /** A digest found late (pod down, empty list until then) still goes out within this long after its time. */
        val DIGEST_WINDOW: Duration = Duration.ofHours(6)

        const val WRAP_UP = "WRAP_UP"
        const val HOMEWORK = "HOMEWORK"

        private val DEFAULT_ZONE: ZoneId = ZoneId.of("Europe/Berlin")
    }
}
