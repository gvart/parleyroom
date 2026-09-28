package com.gvart.parleyroom.ai.service

/**
 * Plain text from the live classroom's rich-text notes (TipTap HTML): one line per paragraph,
 * heading or list item, no markup. Text that is not HTML (e.g. "Tun = machen <- Fehler") is
 * returned unchanged.
 */
object HtmlText {

    private val TAG = Regex("""</?([a-zA-Z][a-zA-Z0-9]*)(\s[^<>]*)?/?>""")
    private val BLOCK_TAGS = setOf("p", "div", "li", "br", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "ul", "ol", "tr", "pre")
    private val ENTITIES = mapOf("&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&nbsp;" to " ")
    private val NUMERIC_ENTITY = Regex("&#(x?)([0-9a-fA-F]+);")

    fun looksLikeHtml(text: String): Boolean =
        TAG.findAll(text).any { it.groupValues[1].lowercase() in BLOCK_TAGS }

    fun toPlainText(text: String): String {
        if (!looksLikeHtml(text)) return text
        val withBreaks = TAG.replace(text) { match -> if (match.groupValues[1].lowercase() in BLOCK_TAGS) "\n" else "" }
        val decoded = NUMERIC_ENTITY.replace(ENTITIES.entries.fold(withBreaks) { acc, (entity, value) -> acc.replace(entity, value) }) {
            val code = it.groupValues[2].toIntOrNull(if (it.groupValues[1].isEmpty()) 10 else 16)
            code?.let { c -> String(Character.toChars(c)) } ?: it.value
        }
        return decoded.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }
}
