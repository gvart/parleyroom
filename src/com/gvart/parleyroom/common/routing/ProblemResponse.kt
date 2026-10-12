package com.gvart.parleyroom.common.routing

import com.gvart.parleyroom.common.transfer.ProblemDetail
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.DefaultJson
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.serializerOrNull

/**
 * Responds with an RFC 9457 body as application/problem+json. Serialized by hand so it never
 * depends on content negotiation (an SSE client's Accept: text/event-stream would turn it into a 406).
 */
suspend fun ApplicationCall.respondProblem(status: HttpStatusCode, problem: ProblemDetail) =
    respondText(
        DefaultJson.encodeToString(ProblemDetail.serializer(), problem),
        ContentType.Application.ProblemJson,
        status,
    )

/**
 * JSON pointer for a RequestValidation reason: validate() messages lead with the property they
 * are about ("inviteExpiryDays must be…", "Title can't be empty"), so the first word is matched
 * against the body's serial names. Null when the reason is not about a single property.
 */
fun validationPointer(body: Any, reason: String): String? {
    val word = reason.substringBefore(' ').trimEnd(':', ',', '.')
    val names = serializerOrNull(body::class.java)?.descriptor?.elementNames ?: return null
    return names.firstOrNull { it == word || it == word.replaceFirstChar(Char::lowercaseChar) }?.let { "/$it" }
}
