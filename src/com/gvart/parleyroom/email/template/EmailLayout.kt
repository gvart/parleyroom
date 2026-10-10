package com.gvart.parleyroom.email.template

import com.gvart.parleyroom.email.config.Brand

/** Robbi's pose in the email header; each is a PNG under resources/email (see scripts/render-robbi.mjs). */
enum class RobbiPose(val resource: String) {
    IDLE("email/robbi-idle.png"),
    WAVE("email/robbi-wave.png"),
    CELEBRATE("email/robbi-celebrate.png");

    val filename: String get() = resource.substringAfterLast('/')

    val bytes: ByteArray by lazy { RobbiPose::class.java.classLoader.getResourceAsStream(resource)!!.use { it.readBytes() } }

    companion object {
        /** The HTML references the inline attachment as `cid:robbi`. */
        const val CONTENT_ID = "robbi"
    }
}

data class RenderedEmail(val subject: String, val html: String, val text: String, val robbi: RobbiPose)

/** Unsubscribe footer of reminder emails. */
data class ReminderFooter(val settingsUrl: String, val unsubscribeUrl: String)

/** Everything one email says; [EmailLayout] turns it into HTML and plain text. All strings are unescaped. */
internal data class EmailContent(
    val lang: EmailLanguage,
    val subject: String,
    /** Inbox preview line. */
    val preheader: String,
    val robbi: RobbiPose,
    val heading: String,
    val paragraphs: List<String>,
    /** Label to value; lines after the first in a value render as a muted second line. */
    val details: List<Pair<String, String>>,
    val ctaLabel: String,
    val ctaUrl: String,
    val linkHint: String,
    val note: String? = null,
    val signoff: List<String>,
    val footerReason: String? = null,
    val footerLinks: List<Pair<String, String>> = emptyList(),
)

/**
 * Table-based, inline-styled email in the portal's Liquid Glass look: light green page, white rounded
 * card, Nunito with system fallbacks, green pill CTA with the portal's 3D bottom edge.
 */
internal object EmailLayout {
    private const val BG = "#EEF7EC"
    private const val CARD = "#FFFFFF"
    private const val INK = "#0E2318"
    private const val INK_2 = "#35493E"
    private const val INK_3 = "#4F6358"
    private const val ACCENT_FACE = "#227600"
    private const val ACCENT_BASE = "#164F00"
    private const val ACCENT_INK = "#1E6B00"
    private const val SOFT = "#F3F9F1"
    private const val HAIR = "#DCE8D8"
    private const val FONT = "'Nunito','Segoe UI',-apple-system,BlinkMacSystemFont,Roboto,'Helvetica Neue',Arial,sans-serif"

    fun html(c: EmailContent, brand: Brand): String = buildString {
        append("""<!DOCTYPE html>
<html lang="${c.lang.tag}" xmlns="http://www.w3.org/1999/xhtml">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="x-apple-disable-message-reformatting">
<meta name="color-scheme" content="light dark">
<meta name="supported-color-schemes" content="light dark">
<title>${esc(c.subject)}</title>
<link href="https://fonts.googleapis.com/css2?family=Nunito:wght@400;600;700;800;900&amp;display=swap" rel="stylesheet">
<style>
  body { margin:0; padding:0; width:100% !important; -webkit-text-size-adjust:100%; }
  a { color:$ACCENT_INK; }
  @media (max-width: 480px) {
    .pr-card { padding:28px 20px !important; border-radius:22px !important; }
    .pr-h1 { font-size:22px !important; }
    .pr-outer { padding:20px 10px !important; }
  }
  @media (prefers-color-scheme: dark) {
    .pr-bg { background:#0B1A12 !important; }
    .pr-card { background:#14281C !important; border-color:#24402E !important; }
    .pr-ink { color:#EAF5E6 !important; }
    .pr-ink2 { color:#C3D6C8 !important; }
    .pr-ink3 { color:#9FB5A6 !important; }
    .pr-soft { background:#1B3324 !important; }
    .pr-accent { color:#8BE04A !important; }
    .pr-hair { background:#24402E !important; }
  }
</style>
</head>
<body class="pr-bg" style="margin:0;padding:0;background:$BG;">
<div style="display:none;max-height:0;overflow:hidden;mso-hide:all;font-size:1px;line-height:1px;color:$BG;">${esc(c.preheader)}&#847;&zwnj;&nbsp;&#847;&zwnj;&nbsp;&#847;&zwnj;&nbsp;</div>
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" class="pr-bg" style="background:$BG;">
<tr><td align="center" class="pr-outer" style="padding:32px 16px;">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="max-width:560px;">
""")
        // Wordmark: the brand tile (first letter) + name with the accent half in green.
        append("""<tr><td align="center" style="padding:0 0 20px;">
<table role="presentation" cellpadding="0" cellspacing="0" border="0"><tr>
<td width="36" height="36" align="center" valign="middle" style="width:36px;height:36px;background:$ACCENT_FACE;border-radius:12px;color:#FFFFFF;font-family:$FONT;font-size:20px;font-weight:900;line-height:36px;">${esc(brand.name.take(1).uppercase())}</td>
<td style="padding-left:10px;font-family:$FONT;font-size:21px;font-weight:900;letter-spacing:-0.02em;color:$INK;" class="pr-ink">${esc(brand.head)}<span class="pr-accent" style="color:$ACCENT_INK;">${esc(brand.accent)}</span></td>
</tr></table>
</td></tr>
""")
        append("""<tr><td class="pr-card" style="background:$CARD;border:1px solid $HAIR;border-radius:28px;padding:36px 36px 32px;">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0">
<tr><td align="center" style="padding:0 0 12px;"><img src="cid:${RobbiPose.CONTENT_ID}" width="96" height="96" alt="Robbi" style="display:block;width:96px;height:96px;border:0;image-rendering:pixelated;"></td></tr>
<tr><td align="center" class="pr-h1 pr-ink" style="font-family:$FONT;font-size:26px;line-height:1.2;font-weight:800;letter-spacing:-0.02em;color:$INK;padding:0 0 12px;">${esc(c.heading)}</td></tr>
""")
        c.paragraphs.forEach {
            append("""<tr><td align="center" class="pr-ink2" style="font-family:$FONT;font-size:16px;line-height:1.55;color:$INK_2;padding:0 0 12px;">${esc(it)}</td></tr>
""")
        }
        if (c.details.isNotEmpty()) {
            append("""<tr><td style="padding:8px 0 4px;">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" class="pr-soft" style="background:$SOFT;border-radius:18px;">
""")
            c.details.forEachIndexed { i, (label, value) ->
                val top = if (i == 0) 18 else 6
                val bottom = if (i == c.details.lastIndex) 18 else 6
                append("""<tr><td style="padding:${top}px 22px ${bottom}px;font-family:$FONT;">
<div class="pr-ink3" style="font-size:11px;line-height:1.3;font-weight:800;letter-spacing:0.08em;text-transform:uppercase;color:$INK_3;">${esc(label)}</div>
<div class="pr-ink" style="font-size:16px;line-height:1.4;font-weight:700;color:$INK;padding-top:2px;">${esc(value.substringBefore('\n'))}</div>
${value.lines().drop(1).joinToString("") { """<div class="pr-ink3" style="font-size:13px;line-height:1.4;color:$INK_3;">${esc(it)}</div>""" }}
</td></tr>
""")
            }
            append("</table>\n</td></tr>\n")
        }
        append("""<tr><td align="center" style="padding:24px 0 8px;">
<table role="presentation" cellpadding="0" cellspacing="0" border="0"><tr>
<td align="center" style="background:$ACCENT_FACE;border-radius:999px;border-bottom:3px solid $ACCENT_BASE;">
<a href="${esc(c.ctaUrl)}" target="_blank" style="display:inline-block;padding:14px 32px;font-family:$FONT;font-size:16px;font-weight:800;line-height:1.2;color:#FFFFFF;text-decoration:none;border-radius:999px;">${esc(c.ctaLabel)}</a>
</td></tr></table>
</td></tr>
<tr><td align="center" class="pr-ink3" style="font-family:$FONT;font-size:12px;line-height:1.5;color:$INK_3;padding:12px 0 0;">${esc(c.linkHint)}<br><a href="${esc(c.ctaUrl)}" style="color:$ACCENT_INK;word-break:break-all;" class="pr-accent">${esc(c.ctaUrl)}</a></td></tr>
""")
        c.note?.let {
            append("""<tr><td align="center" class="pr-ink3" style="font-family:$FONT;font-size:13px;line-height:1.5;color:$INK_3;padding:18px 0 0;">${esc(it)}</td></tr>
""")
        }
        append("""<tr><td style="padding:28px 0 0;"><div class="pr-hair" style="height:1px;line-height:1px;font-size:1px;background:$HAIR;">&nbsp;</div></td></tr>
<tr><td class="pr-ink2" style="font-family:$FONT;font-size:15px;line-height:1.5;color:$INK_2;padding:20px 0 0;" align="center">${c.signoff.joinToString("<br>") { esc(it) }}</td></tr>
</table>
</td></tr>
""")
        append("""<tr><td align="center" class="pr-ink3" style="font-family:$FONT;font-size:12px;line-height:1.6;color:$INK_3;padding:20px 24px 0;">
""")
        c.footerReason?.let { append(esc(it)).append("<br>") }
        if (c.footerLinks.isNotEmpty()) {
            append(c.footerLinks.joinToString(" &nbsp;·&nbsp; ") { (label, url) ->
                """<a href="${esc(url)}" style="color:$INK_3;text-decoration:underline;" class="pr-ink3">${esc(label)}</a>"""
            })
            append("<br>")
        }
        append("© ${esc(brand.name)}\n</td></tr>\n</table>\n</td></tr>\n</table>\n</body>\n</html>\n")
    }

    fun text(c: EmailContent): String = buildString {
        appendLine(c.heading)
        appendLine()
        c.paragraphs.forEach { appendLine(it); appendLine() }
        c.details.forEach { (label, value) -> appendLine("$label: ${value.replace("\n", ", ")}") }
        if (c.details.isNotEmpty()) appendLine()
        appendLine("${c.ctaLabel}: ${c.ctaUrl}")
        c.note?.let { appendLine(); appendLine(it) }
        appendLine()
        c.signoff.forEach { appendLine(it) }
        if (c.footerReason != null || c.footerLinks.isNotEmpty()) {
            appendLine()
            appendLine("--")
            c.footerReason?.let { appendLine(it) }
            c.footerLinks.forEach { (label, url) -> appendLine("$label: $url") }
        }
    }

    private fun esc(s: String): String = buildString(s.length) {
        s.forEach {
            when (it) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(it)
            }
        }
    }
}
