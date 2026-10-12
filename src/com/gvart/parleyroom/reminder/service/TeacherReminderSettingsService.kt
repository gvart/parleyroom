package com.gvart.parleyroom.reminder.service

import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.reminder.data.BeforeLessonMode
import com.gvart.parleyroom.reminder.data.TeacherReminderSettingsTable
import com.gvart.parleyroom.reminder.transfer.AfterLessonSettings
import com.gvart.parleyroom.reminder.transfer.BeforeLessonSettings
import com.gvart.parleyroom.reminder.transfer.TeacherReminderSettingsRequest
import com.gvart.parleyroom.reminder.transfer.TeacherReminderSettingsRequest.Companion.formatTime
import com.gvart.parleyroom.reminder.transfer.TeacherReminderSettingsRequest.Companion.parseTime
import com.gvart.parleyroom.reminder.transfer.TeacherReminderSettingsResponse
import com.gvart.parleyroom.reminder.transfer.WeeklyDigestSettings
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.UserPrincipal
import kotlinx.datetime.toJavaLocalTime
import kotlinx.datetime.toKotlinLocalTime
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.DayOfWeek
import java.time.OffsetDateTime
import java.util.UUID

/** GET/PUT /api/v1/users/me/teacher-reminders; teachers and admins (students 403). */
class TeacherReminderSettingsService {

    fun get(principal: UserPrincipal): TeacherReminderSettingsResponse = transaction {
        requireTeacher(principal)
        response(principal.id)
    }

    fun replace(request: TeacherReminderSettingsRequest, principal: UserPrincipal): TeacherReminderSettingsResponse = transaction {
        requireTeacher(principal)
        TeacherReminderSettingsTable.upsert {
            it[teacherId] = principal.id
            it[afterLessonEnabled] = request.afterLesson.enabled
            it[afterLessonHours] = request.afterLesson.hoursAfter
            it[afterLessonEmail] = request.afterLesson.email
            it[afterLessonTelegram] = request.afterLesson.telegram
            it[beforeLessonEnabled] = request.beforeLesson.enabled
            it[beforeLessonMode] = BeforeLessonMode.valueOf(request.beforeLesson.mode)
            it[beforeLessonTime] = parseTime(request.beforeLesson.time)!!.toKotlinLocalTime()
            it[beforeLessonHours] = request.beforeLesson.hoursBefore
            it[beforeLessonEmail] = request.beforeLesson.email
            it[beforeLessonTelegram] = request.beforeLesson.telegram
            it[digestEnabled] = request.weeklyDigest.enabled
            it[digestWeekday] = DayOfWeek.valueOf(request.weeklyDigest.weekday).value.toShort()
            it[digestTime] = parseTime(request.weeklyDigest.time)!!.toKotlinLocalTime()
            it[digestEmail] = request.weeklyDigest.email
            it[digestTelegram] = request.weeklyDigest.telegram
            it[updatedAt] = OffsetDateTime.now()
        }
        response(principal.id)
    }

    private fun requireTeacher(principal: UserPrincipal) {
        if (principal.role == UserRole.STUDENT) throw ForbiddenException("Teacher reminders are for teachers only")
    }

    private fun response(teacherId: UUID): TeacherReminderSettingsResponse {
        val settings = TeacherReminderSettings.of(listOf(teacherId)).getValue(teacherId)
        val user = UserTable.selectAll().where { UserTable.id eq teacherId }.single()
        return TeacherReminderSettingsResponse(
            afterLesson = settings.afterLesson,
            beforeLesson = settings.beforeLesson,
            weeklyDigest = settings.weeklyDigest,
            timezone = user[UserTable.timezone],
            telegramLinked = user[UserTable.telegramId] != null,
        )
    }
}

/** Stored settings as the request shape (validated values). Must run in a transaction. */
object TeacherReminderSettings {

    /** Every teacher in [teacherIds], with the defaults for those without a row. */
    fun of(teacherIds: Collection<UUID>): Map<UUID, TeacherReminderSettingsRequest> {
        val stored = if (teacherIds.isEmpty()) emptyMap()
        else TeacherReminderSettingsTable.selectAll().where { TeacherReminderSettingsTable.teacherId inList teacherIds }
            .associate { it[TeacherReminderSettingsTable.teacherId].value to fromRow(it) }
        return teacherIds.associateWith { stored[it] ?: TeacherReminderSettingsRequest() }
    }

    /** The unsubscribe link of a teacher reminder email: every email toggle off. */
    fun disableEmail(teacherId: UUID) {
        TeacherReminderSettingsTable.upsert(
            onUpdateExclude = TeacherReminderSettingsTable.columns - setOf(
                TeacherReminderSettingsTable.afterLessonEmail, TeacherReminderSettingsTable.beforeLessonEmail,
                TeacherReminderSettingsTable.digestEmail, TeacherReminderSettingsTable.updatedAt,
            ),
        ) {
            it[TeacherReminderSettingsTable.teacherId] = teacherId
            it[afterLessonEmail] = false
            it[beforeLessonEmail] = false
            it[digestEmail] = false
            it[updatedAt] = OffsetDateTime.now()
        }
    }

    private fun fromRow(row: ResultRow) = TeacherReminderSettingsRequest(
        afterLesson = AfterLessonSettings(
            enabled = row[TeacherReminderSettingsTable.afterLessonEnabled],
            hoursAfter = row[TeacherReminderSettingsTable.afterLessonHours],
            email = row[TeacherReminderSettingsTable.afterLessonEmail],
            telegram = row[TeacherReminderSettingsTable.afterLessonTelegram],
        ),
        beforeLesson = BeforeLessonSettings(
            enabled = row[TeacherReminderSettingsTable.beforeLessonEnabled],
            mode = row[TeacherReminderSettingsTable.beforeLessonMode].name,
            time = formatTime(row[TeacherReminderSettingsTable.beforeLessonTime].toJavaLocalTime()),
            hoursBefore = row[TeacherReminderSettingsTable.beforeLessonHours],
            email = row[TeacherReminderSettingsTable.beforeLessonEmail],
            telegram = row[TeacherReminderSettingsTable.beforeLessonTelegram],
        ),
        weeklyDigest = WeeklyDigestSettings(
            enabled = row[TeacherReminderSettingsTable.digestEnabled],
            weekday = DayOfWeek.of(row[TeacherReminderSettingsTable.digestWeekday].toInt()).name,
            time = formatTime(row[TeacherReminderSettingsTable.digestTime].toJavaLocalTime()),
            email = row[TeacherReminderSettingsTable.digestEmail],
            telegram = row[TeacherReminderSettingsTable.digestTelegram],
        ),
    )
}
