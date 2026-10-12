package com.gvart.parleyroom.telegram

import com.gvart.parleyroom.email.config.Brand
import com.gvart.parleyroom.email.template.EmailLanguage
import com.gvart.parleyroom.email.template.LessonReminderVariant
import com.gvart.parleyroom.reminder.transfer.AttentionReasonType
import com.gvart.parleyroom.reminder.transfer.TeacherDigestLine
import com.gvart.parleyroom.telegram.template.TelegramTemplates
import com.gvart.parleyroom.user.data.UserRole
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramTemplatesTest {

    private val templates = TelegramTemplates(Brand.parse("Sprach|robbe"))

    @Test
    fun `user content is HTML-escaped, the markup is not`() {
        val content = templates.lessonReminder(
            EmailLanguage.EN, LessonReminderVariant.H24, "Dativ <b> & \"Akkusativ\"",
            OffsetDateTime.parse("2030-03-12T17:00:00Z"), ZoneId.of("Europe/Berlin"), UserRole.TEACHER, "Tom & <Jerry>",
        )
        assertEquals(
            "Reminder: <b>Dativ &lt;b&gt; &amp; \"Akkusativ\"</b> is tomorrow at 18:00.\n" +
                "Tuesday, 12 March · 18:00 (Europe/Berlin)\n" +
                "Attendees: Tom &amp; &lt;Jerry&gt;",
            content.text,
        )
        assertEquals("View lesson", content.buttonLabel)

        val homework = templates.homeworkOverdue(EmailLanguage.DE, "a<b")
        assertTrue("<b>a&lt;b</b>" in homework.text, homework.text)
        assertFalse("<b>a<b" in homework.text)
    }

    @Test
    fun `every language has its own copy`() {
        val texts = EmailLanguage.entries.map { templates.homeworkDueTomorrow(it, "HW").text }
        assertEquals(EmailLanguage.entries.size, texts.toSet().size)
        assertTrue("Sprachrobbe" in templates.test(EmailLanguage.UK).text)
    }

    @Test
    fun `teacher reminders escape names and differ per language`() {
        val followUp = templates.teacherFollowUp(EmailLanguage.EN, "<Ann>", "A & B", wrapUp = true, homework = false)
        assertEquals("Your lesson with <b>&lt;Ann&gt;</b> is over (A &amp; B). Still open:\n• Check off what you covered", followUp.text)
        assertEquals("Wrap up lesson", followUp.buttonLabel)

        val digest = templates.teacherDigest(
            EmailLanguage.EN, listOf(TeacherDigestLine("Mia", mapOf(AttentionReasonType.BEHIND to 1, AttentionReasonType.MISMATCH to 2))),
        )
        assertTrue("• <b>Mia</b>: behind schedule, self-assessment mismatches: 2" in digest.text, digest.text)

        val at = OffsetDateTime.parse("2030-03-12T17:00:00Z")
        val preps = EmailLanguage.entries.map { templates.teacherPrep(it, "Mia", "Perfekt", at, ZoneId.of("Europe/Berlin")).text }
        assertEquals(EmailLanguage.entries.size, preps.toSet().size)
        assertTrue(preps.all { "18:00" in it })
    }
}
