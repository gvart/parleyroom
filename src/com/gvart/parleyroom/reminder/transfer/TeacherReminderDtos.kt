package com.gvart.parleyroom.reminder.transfer

import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import com.gvart.parleyroom.reminder.data.BeforeLessonMode
import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** In-app is always on; [email] / [telegram] add those channels (Telegram only while linked and reachable). */
@Serializable
data class AfterLessonSettings(
    val enabled: Boolean = true,
    /** Hours after the lesson's end, 0..48. */
    val hoursAfter: Int = 2,
    val email: Boolean = false,
    val telegram: Boolean = true,
)

/** [mode] DAY_BEFORE: at [time] ("HH:mm", teacher's zone) the day before; HOURS_BEFORE: [hoursBefore] (1..72) ahead. */
@Serializable
data class BeforeLessonSettings(
    val enabled: Boolean = true,
    val mode: String = BeforeLessonMode.DAY_BEFORE.name,
    val time: String = "18:00",
    val hoursBefore: Int = 3,
    val email: Boolean = false,
    val telegram: Boolean = true,
)

/** [weekday] MONDAY..SUNDAY and [time] "HH:mm" in the teacher's zone. */
@Serializable
data class WeeklyDigestSettings(
    val enabled: Boolean = true,
    val weekday: String = DayOfWeek.MONDAY.name,
    val time: String = "08:00",
    val email: Boolean = false,
    val telegram: Boolean = true,
)

/** PUT body: replaces every setting; a missing section or field takes its default. */
@Serializable
data class TeacherReminderSettingsRequest(
    val afterLesson: AfterLessonSettings = AfterLessonSettings(),
    val beforeLesson: BeforeLessonSettings = BeforeLessonSettings(),
    val weeklyDigest: WeeklyDigestSettings = WeeklyDigestSettings(),
) {
    /** Reasons lead with their JSON pointer, which becomes errors[].pointer. */
    fun validate(): ValidationResult {
        val reasons = buildList {
            if (afterLesson.hoursAfter !in 0..48) add("/afterLesson/hoursAfter must be between 0 and 48")
            if (BeforeLessonMode.entries.none { it.name == beforeLesson.mode }) add("/beforeLesson/mode must be DAY_BEFORE or HOURS_BEFORE")
            if (parseTime(beforeLesson.time) == null) add("/beforeLesson/time must be a time HH:mm")
            if (beforeLesson.hoursBefore !in 1..72) add("/beforeLesson/hoursBefore must be between 1 and 72")
            if (DayOfWeek.entries.none { it.name == weeklyDigest.weekday }) add("/weeklyDigest/weekday must be MONDAY..SUNDAY")
            if (parseTime(weeklyDigest.time) == null) add("/weeklyDigest/time must be a time HH:mm")
        }
        return if (reasons.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(reasons)
    }

    companion object {
        private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        fun parseTime(raw: String): LocalTime? = runCatching { LocalTime.parse(raw, HH_MM) }.getOrNull()

        fun formatTime(time: LocalTime): String = time.format(HH_MM)
    }
}

@Serializable
data class TeacherReminderSettingsResponse(
    val afterLesson: AfterLessonSettings,
    val beforeLesson: BeforeLessonSettings,
    val weeklyDigest: WeeklyDigestSettings,
    /** The zone times are read in (the teacher's profile timezone). */
    val timezone: String,
    /** Telegram reminders only reach a linked account the bot may message. */
    val telegramLinked: Boolean,
)

enum class AttentionReasonType { BEHIND, MISMATCH, NOT_WRAPPED_UP, NO_HOMEWORK }

/**
 * [refId]: the goal (BEHIND), milestone (MISMATCH) or lesson (NOT_WRAPPED_UP); none for NO_HOMEWORK.
 * [detail]: progress "<percent>/<expected>" (BEHIND), the milestone or lesson title, or null.
 * [at]: the lesson's start (NOT_WRAPPED_UP), the answer's time (MISMATCH) or the last homework's (NO_HOMEWORK, null = never).
 */
@Serializable
data class AttentionReason(
    val type: AttentionReasonType,
    val refId: String? = null,
    val detail: String? = null,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val at: OffsetDateTime? = null,
)

@Serializable
data class AttentionStudent(
    val studentId: String,
    val studentName: String,
    val reasons: List<AttentionReason>,
)

/** A student in the weekly digest: first name only and how many reasons of each type (most severe first). */
data class TeacherDigestLine(val firstName: String, val reasons: Map<AttentionReasonType, Int>)
