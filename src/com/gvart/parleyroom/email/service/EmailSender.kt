package com.gvart.parleyroom.email.service

/** An image embedded in the HTML body, referenced as `cid:<contentId>`. */
class InlineImage(val contentId: String, val filename: String, val contentType: String, val bytes: ByteArray)

data class OutgoingEmail(
    val to: String,
    val subject: String,
    val html: String,
    val text: String,
    /** Extra headers, e.g. List-Unsubscribe. */
    val headers: Map<String, String> = emptyMap(),
    val inlineImages: List<InlineImage> = emptyList(),
)

fun interface EmailSender {
    /** Never throws: a failure is logged and reported as false. */
    suspend fun send(email: OutgoingEmail): Boolean
}
