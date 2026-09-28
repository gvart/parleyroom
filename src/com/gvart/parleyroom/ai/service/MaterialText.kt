package com.gvart.parleyroom.ai.service

import com.gvart.parleyroom.ai.transfer.TextSourceKind
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/**
 * Text of an uploaded material for AI tag suggestions: PDF pages 1–3 (PDFBox), DOCX
 * `word/document.xml` (zip + StAX, no Apache POI), plain text as is. Anything else, or a file that
 * cannot be read, yields no text (the name alone is used).
 */
object MaterialText {

    const val MAX_CHARS = 6_000
    const val MAX_PDF_BYTES = 20L * 1024 * 1024
    private const val MAX_DOCX_XML_BYTES = 5 * 1024 * 1024
    private const val PDF_PAGES = 3

    private const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    private val TEXT_TYPES = setOf("text/plain", "text/markdown", "text/csv")
    private val TEXT_EXTENSIONS = setOf("txt", "md", "csv")

    private val log = LoggerFactory.getLogger(MaterialText::class.java)
    private val WHITESPACE = Regex("[ \\t\\x0B\\f\\r]+")
    private val BLANK_LINES = Regex("\\n\\s*\\n+")

    data class Extracted(val kind: TextSourceKind, val text: String, val truncated: Boolean)

    /** The kind of text extraction for a stored file, or null if the file type is not supported. */
    fun kindOf(contentType: String?, fileName: String): TextSourceKind? {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase()
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when {
            type == "application/pdf" -> TextSourceKind.PDF
            type == DOCX -> TextSourceKind.DOCX
            type in TEXT_TYPES -> TextSourceKind.TEXT
            extension == "pdf" -> TextSourceKind.PDF
            extension == "docx" -> TextSourceKind.DOCX
            extension in TEXT_EXTENSIONS -> TextSourceKind.TEXT
            else -> null
        }
    }

    /** Extracts and caps the text; any failure (corrupt, encrypted, too big) gives NAME_ONLY. */
    fun extract(kind: TextSourceKind, size: Long?, open: () -> InputStream): Extracted {
        val raw = try {
            when (kind) {
                TextSourceKind.PDF -> if (size != null && size > MAX_PDF_BYTES) null else open().use(::pdf)
                TextSourceKind.DOCX -> open().use(::docx)
                TextSourceKind.TEXT -> open().use { String(it.readNBytes(MAX_CHARS * 4), Charsets.UTF_8) }
                TextSourceKind.NAME_ONLY -> null
            }
        } catch (e: Exception) {
            log.info("Could not extract text from a {} material: {}", kind, e.javaClass.simpleName)
            null
        }
        val text = raw?.let(::normalize).orEmpty()
        if (text.isEmpty()) return Extracted(TextSourceKind.NAME_ONLY, "", false)
        val (capped, truncated) = cap(text)
        return Extracted(kind, capped, truncated)
    }

    fun normalize(text: String): String =
        text.lines().joinToString("\n") { it.replace(WHITESPACE, " ").trim() }.replace(BLANK_LINES, "\n\n").trim()

    /** At most [MAX_CHARS], cut at a word boundary. */
    fun cap(text: String): Pair<String, Boolean> {
        if (text.length <= MAX_CHARS) return text to false
        val cut = text.lastIndexOf(' ', MAX_CHARS).takeIf { it > MAX_CHARS / 2 } ?: MAX_CHARS
        return text.substring(0, cut).trimEnd() to true
    }

    private fun pdf(input: InputStream): String = Loader.loadPDF(input.readAllBytes()).use { document ->
        if (document.isEncrypted) return ""
        PDFTextStripper().apply { startPage = 1; endPage = PDF_PAGES }.getText(document)
    }

    private fun docx(input: InputStream): String {
        val zip = ZipInputStream(input)
        while (true) {
            val entry = zip.nextEntry ?: return ""
            if (entry.name == "word/document.xml") return documentXml(zip.readNBytes(MAX_DOCX_XML_BYTES))
        }
    }

    /** `w:t` runs as text, `w:p` ends a line, `w:tab` / `w:br` become separators. DTDs and external entities are off. */
    private fun documentXml(bytes: ByteArray): String {
        val factory = XMLInputFactory.newFactory().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        }
        val reader = factory.createXMLStreamReader(bytes.inputStream())
        val out = StringBuilder()
        var inText = false
        try {
            while (reader.hasNext() && out.length < MAX_CHARS * 2) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                        "t" -> inText = true
                        "tab" -> out.append(' ')
                        "br" -> out.append('\n')
                    }
                    XMLStreamConstants.END_ELEMENT -> when (reader.localName) {
                        "t" -> inText = false
                        "p" -> out.append('\n')
                    }
                    XMLStreamConstants.CHARACTERS -> if (inText) out.append(reader.text)
                }
            }
        } catch (e: javax.xml.stream.XMLStreamException) {
            // A document.xml cut at the size cap ends mid-element: keep what was read.
            if (out.isEmpty()) throw e
        } finally {
            reader.close()
        }
        return out.toString()
    }
}
