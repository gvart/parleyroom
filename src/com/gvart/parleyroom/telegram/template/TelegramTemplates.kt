package com.gvart.parleyroom.telegram.template

import com.gvart.parleyroom.email.config.Brand
import com.gvart.parleyroom.email.template.EmailLanguage
import com.gvart.parleyroom.email.template.EmailLanguage.DE
import com.gvart.parleyroom.email.template.EmailLanguage.EN
import com.gvart.parleyroom.email.template.EmailLanguage.RU
import com.gvart.parleyroom.email.template.EmailLanguage.UK
import com.gvart.parleyroom.email.template.LessonReminderVariant
import com.gvart.parleyroom.email.template.digestReason
import com.gvart.parleyroom.reminder.transfer.TeacherDigestLine
import com.gvart.parleyroom.user.data.UserRole
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A message in Telegram HTML and the label of its one button. */
data class TelegramContent(val text: String, val buttonLabel: String)

/** Short reminder messages in the same languages as the emails (see emailLanguageFor). */
class TelegramTemplates(private val brand: Brand) {

    fun test(lang: EmailLanguage): TelegramContent {
        val name = escape(brand.name)
        return when (lang) {
            EN -> TelegramContent("Notifications from <b>$name</b> are working. Lesson and homework reminders will show up here.", "Open $name")
            RU -> TelegramContent("Уведомления от <b>$name</b> работают. Напоминания об уроках и домашних заданиях будут приходить сюда.", "Открыть $name")
            UK -> TelegramContent("Сповіщення від <b>$name</b> працюють. Нагадування про уроки й домашні завдання надходитимуть сюди.", "Відкрити $name")
            DE -> TelegramContent("Benachrichtigungen von <b>$name</b> funktionieren. Erinnerungen an Stunden und Hausaufgaben kommen hier an.", "$name öffnen")
        }
    }

    /** [counterpartName]: the teacher for a student; the student(s) or club for a teacher; null to omit. */
    fun lessonReminder(
        lang: EmailLanguage,
        variant: LessonReminderVariant,
        lessonTitle: String,
        startsAt: OffsetDateTime,
        zone: ZoneId,
        recipientRole: UserRole,
        counterpartName: String?,
    ): TelegramContent {
        val title = "<b>${escape(lessonTitle)}</b>"
        val time = startsAt.atZoneSameInstant(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
        val soon = variant == LessonReminderVariant.H1
        val student = recipientRole == UserRole.STUDENT
        val counterpart = counterpartName?.let(::escape)
        val (headline, counterpartLine, label) = when (lang) {
            EN -> Triple(
                if (soon) "$title starts in 1 hour, at $time." else "Reminder: $title is tomorrow at $time.",
                counterpart?.let { (if (student) "Teacher: " else "Attendees: ") + it },
                "View lesson",
            )
            RU -> Triple(
                if (soon) "$title начнётся через час, в $time." else "Напоминание: $title завтра в $time.",
                counterpart?.let { (if (student) "Преподаватель: " else "Участники: ") + it },
                "Открыть урок",
            )
            UK -> Triple(
                if (soon) "$title почнеться за годину, о $time." else "Нагадування: $title завтра о $time.",
                counterpart?.let { (if (student) "Викладач: " else "Учасники: ") + it },
                "Відкрити урок",
            )
            DE -> Triple(
                if (soon) "$title beginnt in einer Stunde, um $time." else "Erinnerung: $title ist morgen um $time.",
                counterpart?.let { (if (student) "Lehrkraft: " else "Teilnehmende: ") + it },
                "Zur Stunde",
            )
        }
        return TelegramContent(listOfNotNull(headline, dateTime(lang, startsAt, zone), counterpartLine).joinToString("\n"), label)
    }

    fun homeworkDueTomorrow(lang: EmailLanguage, assignmentTitle: String): TelegramContent {
        val t = "<b>${escape(assignmentTitle)}</b>"
        return when (lang) {
            EN -> TelegramContent("Homework due tomorrow: $t\nA few minutes today and you're done.", "Open homework")
            RU -> TelegramContent("Домашнее задание на завтра: $t\nПара минут сегодня — и готово.", "Открыть задание")
            UK -> TelegramContent("Домашнє завдання на завтра: $t\nКілька хвилин сьогодні — і готово.", "Відкрити завдання")
            DE -> TelegramContent("Hausaufgabe bis morgen: $t\nEin paar Minuten heute, und du bist fertig.", "Zur Hausaufgabe")
        }
    }

    fun homeworkOverdue(lang: EmailLanguage, assignmentTitle: String): TelegramContent {
        val t = "<b>${escape(assignmentTitle)}</b>"
        return when (lang) {
            EN -> TelegramContent("Overdue homework: $t\nNo stress — you can still hand it in.", "Finish homework")
            RU -> TelegramContent("Просроченное задание: $t\nНичего страшного — его ещё можно сдать.", "Доделать задание")
            UK -> TelegramContent("Прострочене завдання: $t\nНічого страшного — його ще можна здати.", "Доробити завдання")
            DE -> TelegramContent("Überfällige Hausaufgabe: $t\nKein Stress – du kannst sie noch abgeben.", "Hausaufgabe abschließen")
        }
    }

    /** To the teacher after a 1:1: what is still open. Only the student's first name. */
    fun teacherFollowUp(lang: EmailLanguage, studentFirstName: String, lessonTitle: String, wrapUp: Boolean, homework: Boolean): TelegramContent {
        val n = "<b>${escape(studentFirstName)}</b>"
        val t = escape(lessonTitle)
        val (headline, wrapLine, homeworkLine, label) = when (lang) {
            EN -> listOf("Your lesson with $n is over ($t). Still open:", "• Check off what you covered", "• Create homework", "Wrap up lesson")
            RU -> listOf("Урок закончился ($n, $t). Осталось:", "• Отметить, что прошли", "• Задать домашнее задание", "Подвести итоги")
            UK -> listOf("Урок закінчився ($n, $t). Залишилося:", "• Позначити, що пройшли", "• Задати домашнє завдання", "Підбити підсумки")
            DE -> listOf("Deine Stunde mit $n ist vorbei ($t). Noch offen:", "• Abhaken, was ihr geschafft habt", "• Hausaufgabe erstellen", "Stunde abschließen")
        }
        return TelegramContent(listOfNotNull(headline, wrapLine.takeIf { wrapUp }, homeworkLine.takeIf { homework }).joinToString("\n"), label)
    }

    /** To the teacher before a 1:1 that is not prepared yet. */
    fun teacherPrep(lang: EmailLanguage, studentFirstName: String, lessonTitle: String, startsAt: OffsetDateTime, zone: ZoneId): TelegramContent {
        val n = "<b>${escape(studentFirstName)}</b>"
        val t = escape(lessonTitle)
        val (headline, label) = when (lang) {
            EN -> "Prepare your lesson with $n: $t." to "Prepare lesson"
            RU -> "Подготовка к уроку: $n, $t." to "Подготовить урок"
            UK -> "Підготовка до уроку: $n, $t." to "Підготувати урок"
            DE -> "Bereite deine Stunde mit $n vor: $t." to "Stunde vorbereiten"
        }
        return TelegramContent("$headline\n${dateTime(lang, startsAt, zone)}", label)
    }

    /** The weekly overview: one line per student, first name and reason counts only. */
    fun teacherDigest(lang: EmailLanguage, students: List<TeacherDigestLine>): TelegramContent {
        val (headline, label) = when (lang) {
            EN -> "<b>Weekly overview</b> — these students could use some attention:" to "Open dashboard"
            RU -> "<b>Обзор недели</b> — этим ученикам стоит уделить внимание:" to "Открыть панель"
            UK -> "<b>Огляд тижня</b> — цим учням варто приділити увагу:" to "Відкрити панель"
            DE -> "<b>Wochenüberblick</b> – diese Lernenden brauchen etwas Aufmerksamkeit:" to "Zum Dashboard"
        }
        val lines = students.map { line ->
            "• <b>${escape(line.firstName)}</b>: " + line.reasons.entries.joinToString(", ") { (type, count) -> digestReason(lang, type, count) }
        }
        return TelegramContent((listOf(headline) + lines).joinToString("\n"), label)
    }

    /** e.g. "Wednesday, 15 October · 18:00 (Europe/Berlin)" in the recipient's language and zone. */
    private fun dateTime(lang: EmailLanguage, at: OffsetDateTime, zone: ZoneId): String {
        val pattern = if (lang == DE) "EEEE, d. MMMM · HH:mm" else "EEEE, d MMMM · HH:mm"
        val formatted = at.atZoneSameInstant(zone).format(DateTimeFormatter.ofPattern(pattern, lang.locale))
        return "${formatted.replaceFirstChar { it.titlecase(lang.locale) }} (${zone.id.replace('_', ' ')})"
    }

    companion object {
        /** The three characters Telegram's HTML parse mode requires escaped in text. */
        fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
