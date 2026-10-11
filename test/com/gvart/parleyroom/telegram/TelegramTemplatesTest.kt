package com.gvart.parleyroom.telegram

import com.gvart.parleyroom.email.config.Brand
import com.gvart.parleyroom.email.template.EmailLanguage
import com.gvart.parleyroom.email.template.LessonReminderVariant
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
}
