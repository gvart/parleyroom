package com.gvart.parleyroom.telegram.routing

import com.gvart.parleyroom.common.routing.requirePrincipal
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.common.transfer.exception.ConflictException
import com.gvart.parleyroom.telegram.service.TelegramNotifier
import com.gvart.parleyroom.telegram.service.TelegramSendResult
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

fun Application.configureTelegramRouting() {
    val notifier: TelegramNotifier by dependencies

    routing {
        authenticate {
            post("/api/v1/users/me/telegram/test") {
                when (notifier.sendTest(call.requirePrincipal().id)) {
                    TelegramSendResult.SENT -> call.respond(HttpStatusCode.NoContent)
                    TelegramSendResult.FORBIDDEN ->
                        throw ConflictException("Telegram refused the message: start the bot or unblock it", code = "TELEGRAM_UNREACHABLE")
                    TelegramSendResult.FAILED -> call.respond(
                        HttpStatusCode.BadGateway,
                        ProblemDetail.of(HttpStatusCode.BadGateway, "Telegram could not be reached", "TELEGRAM_SEND_FAILED"),
                    )
                }
            }.describe {
                summary = "Send a Telegram test message"
                description = "Sends a short localized \"notifications are working\" message to the current user's linked Telegram account, " +
                    "ignoring the reminder toggles. Success clears telegramUnreachable; a 403 from Telegram (bot blocked or never started) sets it."
                responses {
                    HttpStatusCode.NoContent { description = "Message sent; the user is reachable again" }
                    HttpStatusCode.BadRequest {
                        description = "TELEGRAM_NOT_LINKED: no Telegram account is linked"
                        schema = jsonSchema<ProblemDetail>()
                    }
                    HttpStatusCode.Conflict {
                        description = "TELEGRAM_UNREACHABLE: Telegram refused the message (403); the user is marked unreachable"
                        schema = jsonSchema<ProblemDetail>()
                    }
                    HttpStatusCode.BadGateway {
                        description = "TELEGRAM_SEND_FAILED: Telegram failed or timed out"
                        schema = jsonSchema<ProblemDetail>()
                    }
                    HttpStatusCode.Unauthorized {
                        description = "Missing or invalid authentication token"
                        schema = jsonSchema<ProblemDetail>()
                    }
                }
            }
        }
    }
}
