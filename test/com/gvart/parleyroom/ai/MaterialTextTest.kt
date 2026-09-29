package com.gvart.parleyroom.ai

import com.gvart.parleyroom.ai.service.MaterialSource
import com.gvart.parleyroom.ai.service.MaterialSources
import com.gvart.parleyroom.ai.service.MaterialText
import com.gvart.parleyroom.ai.transfer.TextSourceKind
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaterialTextTest {

    @Test
    fun `text is capped at a word boundary`() {
        val text = "Wort ".repeat(2_000)
        val (capped, truncated) = MaterialText.cap(MaterialText.normalize(text))
        assertTrue(truncated)
        assertTrue(capped.length <= MaterialText.MAX_CHARS)
        assertTrue(capped.endsWith("Wort"))
    }

    @Test
    fun `plain text is normalised and short text is not truncated`() {
        val extracted = MaterialText.extract(TextSourceKind.TEXT, null) { "  Hallo \t Welt \r\n\n\n\nTschüss ".byteInputStream() }
        assertEquals(TextSourceKind.TEXT, extracted.kind)
        assertEquals("Hallo Welt\n\nTschüss", extracted.text)
        assertEquals(false, extracted.truncated)
    }

    @Test
    fun `file kind comes from the content type, else the extension`() {
        assertEquals(TextSourceKind.PDF, MaterialText.kindOf("application/pdf", "x.bin"))
        assertEquals(TextSourceKind.DOCX, MaterialText.kindOf("application/octet-stream", "Blatt.DOCX"))
        assertEquals(TextSourceKind.TEXT, MaterialText.kindOf("text/plain; charset=utf-8", "x"))
        assertNull(MaterialText.kindOf("image/png", "bild.png"))
    }

    @Test
    fun `oversized PDFs and broken DOCX files give no text`() {
        val big = MaterialText.extract(TextSourceKind.PDF, MaterialText.MAX_PDF_BYTES + 1) { error("must not be read") }
        assertEquals(TextSourceKind.NAME_ONLY, big.kind)
        val broken = MaterialText.extract(TextSourceKind.DOCX, 10) { "not a zip".byteInputStream() }
        assertEquals(TextSourceKind.NAME_ONLY, broken.kind)
    }

    @Test
    fun `tag suggestions keep the small limits, word extraction reads more`() {
        assertEquals(MaterialText.Limits(6_000, 3), MaterialText.Limits.TAGS)
        val text = "Wort ".repeat(4_000)
        val tags = MaterialText.extract(TextSourceKind.TEXT, null) { text.byteInputStream() }
        assertTrue(tags.truncated)
        assertTrue(tags.text.length <= MaterialText.MAX_CHARS)
        val words = MaterialText.extract(TextSourceKind.TEXT, null, MaterialText.Limits.WORDS) { text.byteInputStream() }
        assertEquals(false, words.truncated)
        assertEquals(text.trim(), words.text)
    }

    @Test
    fun `materials share the total budget fairly`() {
        fun source(chars: Int) = MaterialSource(UUID.randomUUID(), "m", TextSourceKind.TEXT, "a ".repeat(chars / 2).trim(), false)
        val (short, long1, long2) = MaterialSources.withinBudget(listOf(source(10_000), source(40_000), source(40_000)))
        assertEquals(false, short.truncated)
        assertTrue(long1.truncated && long2.truncated)
        assertTrue(short.text.length + long1.text.length + long2.text.length <= MaterialSources.TOTAL_CHARS)
        assertTrue(long1.text.length > 20_000, "the short one leaves its share to the others")
    }
}
