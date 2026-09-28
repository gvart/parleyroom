package com.gvart.parleyroom.ai

import com.gvart.parleyroom.ai.service.MaterialText
import com.gvart.parleyroom.ai.transfer.TextSourceKind
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
}
