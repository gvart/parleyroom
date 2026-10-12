package com.gvart.parleyroom.reminder.data

import com.gvart.parleyroom.user.data.UserTable
import kotlinx.datetime.LocalTime
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.datetime.time
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

enum class BeforeLessonMode { DAY_BEFORE, HOURS_BEFORE }

enum class TeacherReminderKind { AFTER_LESSON, BEFORE_LESSON, WEEKLY_DIGEST }

/** One row per teacher who saved settings or unsubscribed; without one the defaults (V38) apply. */
object TeacherReminderSettingsTable : Table("teacher_reminder_settings") {
    val teacherId = reference("teacher_id", UserTable, onDelete = ReferenceOption.CASCADE)
    val afterLessonEnabled = bool("after_lesson_enabled").default(true)
    val afterLessonHours = integer("after_lesson_hours").default(2)
    val afterLessonEmail = bool("after_lesson_email").default(false)
    val afterLessonTelegram = bool("after_lesson_telegram").default(true)
    val beforeLessonEnabled = bool("before_lesson_enabled").default(true)
    val beforeLessonMode = enumerationByName<BeforeLessonMode>("before_lesson_mode", 16).default(BeforeLessonMode.DAY_BEFORE)
    val beforeLessonTime = time("before_lesson_time").default(LocalTime(18, 0))
    val beforeLessonHours = integer("before_lesson_hours").default(3)
    val beforeLessonEmail = bool("before_lesson_email").default(false)
    val beforeLessonTelegram = bool("before_lesson_telegram").default(true)
    val digestEnabled = bool("digest_enabled").default(true)
    /** ISO day of week, 1 = Monday. */
    val digestWeekday = short("digest_weekday").default(1)
    val digestTime = time("digest_time").default(LocalTime(8, 0))
    val digestEmail = bool("digest_email").default(false)
    val digestTelegram = bool("digest_telegram").default(true)
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(teacherId)
}

/** In-app reminders sent, claimed first (unique on kind, ref_id, teacher, variant). */
object TeacherReminderLogTable : UUIDTable("teacher_reminder_log") {
    val teacherId = reference("teacher_id", UserTable, onDelete = ReferenceOption.CASCADE)
    val kind = enumerationByName<TeacherReminderKind>("kind", 32)
    val refId = javaUUID("ref_id")
    val variant = varchar("variant", 32)
    val sentAt = timestampWithTimeZone("sent_at")
}
