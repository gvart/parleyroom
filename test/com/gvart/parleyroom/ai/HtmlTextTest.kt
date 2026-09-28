package com.gvart.parleyroom.ai

import com.gvart.parleyroom.ai.service.HtmlText
import kotlin.test.Test
import kotlin.test.assertEquals

class HtmlTextTest {

    @Test
    fun `rich-text notes become one line per paragraph and list item`() {
        val html = "<h2>Wörter</h2><p>die <strong>Gießkanne</strong></p><ul><li><p>Blumen gießen</p></li><li>Tun = machen</li></ul>" +
                "<p>Darum musst du dich kümmern<br>Größe &amp; Gewicht &lt;3 &#252;</p><p></p>"
        assertEquals("Wörter\ndie Gießkanne\nBlumen gießen\nTun = machen\nDarum musst du dich kümmern\nGröße & Gewicht <3 ü",
            HtmlText.toPlainText(html))
    }

    @Test
    fun `plain notes are left alone`() {
        assertEquals(ANNA_NOTES, HtmlText.toPlainText(ANNA_NOTES))
        assertEquals("a < b und <- Fehler", HtmlText.toPlainText("a < b und <- Fehler"))
    }
}
