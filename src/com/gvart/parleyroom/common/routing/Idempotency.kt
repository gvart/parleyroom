package com.gvart.parleyroom.common.routing

import com.gvart.parleyroom.common.service.IdempotencyService
import com.gvart.parleyroom.common.service.IdempotencyService.Claim
import com.gvart.parleyroom.common.transfer.ProblemDetail
import io.ktor.http.ContentType
import io.ktor.openapi.Operation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.DefaultJson
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import kotlinx.serialization.serializer
import java.security.MessageDigest

const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
const val IDEMPOTENT_REPLAYED_HEADER = "Idempotent-Replayed"

/**
 * Responds with [create]'s result. Without an `Idempotency-Key` header this is a plain respond. With one, the first
 * successful response is stored for 24h per user and key and replayed for a retry with the same route and [request]
 * (as parsed, so formatting does not matter); another route or payload is 422 IDEMPOTENCY_KEY_REUSED and a retry
 * while the first is still running 409 IDEMPOTENCY_REQUEST_IN_PROGRESS. Errors are not stored: a failed request
 * frees the key.
 */
suspend inline fun <reified Req : Any, reified Res : Any> ApplicationCall.respondIdempotent(
    idempotency: IdempotencyService,
    request: Req,
    status: HttpStatusCode,
    noinline create: suspend () -> Res,
) {
    val key = this.request.headers[IDEMPOTENCY_KEY_HEADER] ?: return respond(status, create())
    respondWithIdempotencyKey(idempotency, key, DefaultJson.encodeToString(serializer<Req>(), request), status) {
        DefaultJson.encodeToString(serializer<Res>(), create())
    }
}

suspend fun ApplicationCall.respondWithIdempotencyKey(
    idempotency: IdempotencyService,
    key: String,
    requestJson: String,
    status: HttpStatusCode,
    create: suspend () -> String,
) {
    if (key.isBlank() || key.length > 255) {
        return respondProblem(
            HttpStatusCode.BadRequest,
            ProblemDetail.of(HttpStatusCode.BadRequest, "$IDEMPOTENCY_KEY_HEADER must be 1..255 characters", "IDEMPOTENCY_KEY_INVALID"),
        )
    }
    val userId = requirePrincipal().id
    val hash = MessageDigest.getInstance("SHA-256").digest(requestJson.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    when (val claim = idempotency.claim(userId, key, request.httpMethod.value, request.path(), hash)) {
        Claim.Acquired -> {
            val body = try {
                create()
            } catch (e: Throwable) {
                idempotency.release(userId, key)
                throw e
            }
            idempotency.complete(userId, key, status.value, body)
            respondText(body, ContentType.Application.Json, status)
        }
        is Claim.Replay -> {
            response.header(IDEMPOTENT_REPLAYED_HEADER, "true")
            respondText(claim.body, ContentType.Application.Json, HttpStatusCode.fromValue(claim.status))
        }
        Claim.Mismatch -> respondProblem(
            HttpStatusCode.UnprocessableEntity,
            ProblemDetail.of(
                HttpStatusCode.UnprocessableEntity,
                "$IDEMPOTENCY_KEY_HEADER was already used for a different request",
                "IDEMPOTENCY_KEY_REUSED",
            ),
        )
        Claim.InProgress -> respondProblem(
            HttpStatusCode.Conflict,
            ProblemDetail.of(
                HttpStatusCode.Conflict,
                "A request with this $IDEMPOTENCY_KEY_HEADER is still being processed",
                "IDEMPOTENCY_REQUEST_IN_PROGRESS",
            ),
        )
    }
}

/** Documents the optional header on a create route (inside `describe {}`). */
fun Operation.Builder.idempotencyKeyHeader() {
    parameters {
        header(IDEMPOTENCY_KEY_HEADER) {
            description = "Optional, 1..255 chars (a UUID per submit). A retry with the same key, route and body " +
                    "within 24h replays the first successful response (header $IDEMPOTENT_REPLAYED_HEADER: true); " +
                    "another route or body is 422 IDEMPOTENCY_KEY_REUSED, a retry while the first still runs " +
                    "409 IDEMPOTENCY_REQUEST_IN_PROGRESS, a bad key 400 IDEMPOTENCY_KEY_INVALID. Errors are not stored."
            required = false
        }
    }
}
