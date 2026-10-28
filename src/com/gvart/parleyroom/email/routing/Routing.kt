package com.gvart.parleyroom.email.routing

import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.email.service.EmailPreferenceService
import com.gvart.parleyroom.email.transfer.UnsubscribeRequest
import com.gvart.parleyroom.email.transfer.UnsubscribeResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

fun Application.configureEmailRouting() {
    val preferences: EmailPreferenceService by dependencies

    routing {
        post("/api/v1/email/unsubscribe") {
            // Mail clients (RFC 8058) POST a form body with the token in the URL; the portal sends JSON.
            val token = call.request.queryParameters["token"]
                ?: if (call.request.contentType().match(ContentType.Application.Json)) call.receive<UnsubscribeRequest>().token else null
            call.respond(HttpStatusCode.OK, UnsubscribeResponse(preferences.unsubscribe(token)))
        }.describe {
            summary = "Unsubscribe from a reminder email category"
            description = "Public. Takes the token from an email's unsubscribe link, either as JSON { token } (portal page) " +
                "or as ?token= with a List-Unsubscribe=One-Click form body (RFC 8058 one-click from mail clients). " +
                "Switches the token's category (LESSON_, HOMEWORK_, TEACHER_ or VOCAB_REMINDERS) off; idempotent. 400 UNSUBSCRIBE_TOKEN_INVALID for a bad token."
            parameters { query("token") { description = "Unsubscribe token (one-click); alternative to the JSON body"; required = false } }
            requestBody { schema = jsonSchema<UnsubscribeRequest>() }
            responses {
                HttpStatusCode.OK { schema = jsonSchema<UnsubscribeResponse>() }
                HttpStatusCode.BadRequest { schema = jsonSchema<ProblemDetail>() }
            }
        }
    }
}
