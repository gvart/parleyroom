package com.gvart.parleyroom.email

import com.gvart.parleyroom.email.config.Brand
import com.gvart.parleyroom.email.template.EmailLanguage
import com.gvart.parleyroom.email.template.EmailTemplates
import com.gvart.parleyroom.email.template.LessonReminderVariant
import com.gvart.parleyroom.email.template.RenderedEmail
import com.gvart.parleyroom.email.template.ReminderFooter
import com.gvart.parleyroom.email.template.RobbiPose
import com.gvart.parleyroom.email.template.emailLanguageFor
import com.gvart.parleyroom.user.data.UserRole
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmailTemplatesTest {

    private val templates = EmailTemplates(Brand.parse("Sprach|robbe"))

    @Test
    fun `every template renders in every language without leftovers`() {
        for ((name, lang, email) in renderAll(templates)) {
            val where = "$name/${lang.tag}"
            for (part in listOf(email.subject, email.html, email.text)) {
                listOf("{{", "}}", "null", "\${", "Sprach|robbe", "Parley").forEach {
                    assertFalse(it in part, "$where contains '$it'")
                }
            }
            assertTrue("Sprachrobbe" in email.text, "$where: brand missing in text")
            assertTrue(">Sprach<span" in email.html, "$where: wordmark missing")
            assertTrue(">robbe</span>" in email.html, "$where: wordmark accent missing")
            assertTrue("cid:robbi" in email.html, "$where: Robbi missing")
            assertTrue("lang=\"${lang.tag}\"" in email.html, where)
            assertTrue(email.subject.isNotBlank(), where)
        }
    }

    @Test
    fun `reminders have an unsubscribe footer and invites do not`() {
        for ((name, lang, email) in renderAll(templates)) {
            val reminder = !name.startsWith("invite")
            assertEquals(reminder, "https://app.test/unsubscribe?token=t" in email.html, "$name/${lang.tag}")
            assertEquals(reminder, "https://app.test/student/settings" in email.text, "$name/${lang.tag}")
        }
    }

    @Test
    fun `user input is escaped`() {
        val email = templates.invite(EmailLanguage.EN, "<b>Eve</b> & co", UserRole.STUDENT, "https://app.test/r?a=1&b=2", NOW)
        assertTrue("&lt;b&gt;Eve&lt;/b&gt; &amp; co" in email.html)
        assertFalse("<b>Eve</b>" in email.html)
        assertTrue("https://app.test/r?a=1&amp;b=2" in email.html)
        assertTrue("<b>Eve</b> & co" in email.text)
    }

    @Test
    fun `dates use the recipient's language and zone`() {
        val at = OffsetDateTime.of(2026, 10, 14, 16, 0, 0, 0, ZoneOffset.UTC)
        val berlin = ZoneId.of("Europe/Berlin")
        val footer = ReminderFooter("s", "u")
        fun text(lang: EmailLanguage) =
            templates.lessonReminder(lang, LessonReminderVariant.H24, "L", at, berlin, UserRole.STUDENT, null, "x", footer).text
        assertTrue("Среда, 14 октября · 18:00, Europe/Berlin" in text(EmailLanguage.RU), text(EmailLanguage.RU))
        assertTrue("Середа, 14 жовтня · 18:00" in text(EmailLanguage.UK), text(EmailLanguage.UK))
        assertTrue("Mittwoch, 14. Oktober · 18:00" in text(EmailLanguage.DE), text(EmailLanguage.DE))
        assertTrue("Wednesday, 14 October · 18:00" in text(EmailLanguage.EN), text(EmailLanguage.EN))
    }

    @Test
    fun `students read emails in their native language, others in the UI locale`() {
        assertEquals(EmailLanguage.UK, emailLanguageFor(UserRole.STUDENT, "ru", "uk"))
        assertEquals(EmailLanguage.DE, emailLanguageFor(UserRole.STUDENT, "de", null))
        assertEquals(EmailLanguage.DE, emailLanguageFor(UserRole.TEACHER, "de", "uk"))
        assertEquals(EmailLanguage.EN, emailLanguageFor(UserRole.ADMIN, "en", null))
        assertEquals(EmailLanguage.RU, emailLanguageFor(UserRole.TEACHER, "xx", null))
    }

    /** Writes every email as a browsable HTML file when EMAIL_PREVIEW_DIR is set (for screenshots). */
    @Test
    fun `write previews`() {
        val dir = System.getenv("EMAIL_PREVIEW_DIR")?.let(::File) ?: return
        dir.mkdirs()
        for ((name, lang, email) in renderAll(templates)) {
            val dataUri = "data:image/png;base64," + Base64.encode(email.robbi.bytes)
            File(dir, "$name-${lang.tag}.html").writeText(email.html.replace("cid:${RobbiPose.CONTENT_ID}", dataUri))
            File(dir, "$name-${lang.tag}.txt").writeText("Subject: ${email.subject}\n\n${email.text}")
        }
    }

    companion object {
        val NOW: OffsetDateTime = OffsetDateTime.of(2026, 10, 11, 9, 30, 0, 0, ZoneOffset.UTC)

        fun renderAll(templates: EmailTemplates): List<Triple<String, EmailLanguage, RenderedEmail>> {
            val zone = ZoneId.of("Europe/Berlin")
            val footer = ReminderFooter("https://app.test/student/settings", "https://app.test/unsubscribe?token=t")
            return EmailLanguage.entries.flatMap { lang ->
                listOf(
                    "invite" to templates.invite(lang, "Anna Schmidt", UserRole.STUDENT, "https://app.test/register?token=abc", NOW.plusDays(7), zone),
                    "invite-teacher" to templates.invite(lang, "Anna Schmidt", UserRole.TEACHER, "https://app.test/register?token=abc", NOW.plusDays(7), zone),
                    "lessonReminder24h" to templates.lessonReminder(
                        lang, LessonReminderVariant.H24, "Perfekt mit haben und sein", NOW.plusDays(1), zone, UserRole.STUDENT, "Anna Schmidt", "https://app.test/student/lessons", footer,
                    ),
                    "lessonReminder1h" to templates.lessonReminder(
                        lang, LessonReminderVariant.H1, "Konversationsklub", NOW.plusHours(1), zone, UserRole.TEACHER, "Olena, Dmitri +2", "https://app.test/teacher/calendar", footer,
                    ),
                    "homeworkDueTomorrow" to templates.homeworkDueTomorrow(lang, "Wechselpräpositionen", NOW.plusDays(1), zone, "https://app.test/student/homework/1", footer),
                    "homeworkOverdue" to templates.homeworkOverdue(lang, "Wechselpräpositionen", NOW.minusDays(2), zone, "https://app.test/student/homework/1", footer),
                ).map { (name, email) -> Triple(name, lang, email) }
            }
        }
    }
}
