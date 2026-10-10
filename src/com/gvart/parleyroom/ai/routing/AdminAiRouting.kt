package com.gvart.parleyroom.ai.routing

import com.gvart.parleyroom.ai.service.AiModelSettingsService
import com.gvart.parleyroom.ai.service.AiUsageLedger
import com.gvart.parleyroom.ai.transfer.AiSettingsResponse
import com.gvart.parleyroom.ai.transfer.AiUsageResponse
import com.gvart.parleyroom.ai.transfer.AiUseCaseSetting
import com.gvart.parleyroom.ai.transfer.UpdateAiModelRequest
import com.gvart.parleyroom.common.routing.requirePrincipal
import com.gvart.parleyroom.common.service.AuthorizationHelper.requireAdmin
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.time.LocalDate
import java.time.ZoneOffset

fun Application.configureAdminAiRouting() {
    val models: AiModelSettingsService by dependencies
    val ledger: AiUsageLedger by dependencies

    routing {
        authenticate {
            route("/api/v1/admin/ai") {
                get("/settings") {
                    requireAdmin(call.requirePrincipal())
                    call.respond(HttpStatusCode.OK, models.settings())
                }.describe {
                    summary = "AI model routing (admin)"
                    description = "The model of every AI use case and the selectable (priced) models. Admin only."
                    responses {
                        HttpStatusCode.OK { schema = jsonSchema<AiSettingsResponse>() }
                        HttpStatusCode.Forbidden { schema = jsonSchema<ProblemDetail>() }
                    }
                }

                put<UpdateAiModelRequest>("/settings/{useCase}") { request ->
                    val principal = call.requirePrincipal()
                    requireAdmin(principal)
                    call.respond(HttpStatusCode.OK, models.update(call.parameters["useCase"].orEmpty(), request.model, principal))
                }.describe {
                    summary = "Set the model of an AI use case (admin)"
                    description = "Applies to the next call or job. 400 unknown_model when the model has no configured price, " +
                            "400 unknown_use_case for a use case other than GENERATE|REFINE|FILL_TRANSLATIONS|SUGGEST_TAGS|SENTENCE_FEEDBACK."
                    parameters { path("useCase") { description = "GENERATE|REFINE|FILL_TRANSLATIONS|SUGGEST_TAGS|SENTENCE_FEEDBACK" } }
                    requestBody { schema = jsonSchema<UpdateAiModelRequest>() }
                    responses {
                        HttpStatusCode.OK { schema = jsonSchema<AiUseCaseSetting>() }
                        HttpStatusCode.BadRequest { schema = jsonSchema<ProblemDetail>() }
                        HttpStatusCode.Forbidden { schema = jsonSchema<ProblemDetail>() }
                    }
                }

                get("/usage") {
                    requireAdmin(call.requirePrincipal())
                    val params = call.request.queryParameters
                    val to = params["to"]?.let { parseDay(it, "to") } ?: LocalDate.now(ZoneOffset.UTC)
                    val from = params["from"]?.let { parseDay(it, "from") } ?: to.minusDays(DEFAULT_DAYS - 1)
                    if (from.isAfter(to)) throw BadRequestException("from must not be after to", code = "VALIDATION_FAILED")
                    if (from.plusDays(MAX_DAYS) <= to) throw BadRequestException("At most $MAX_DAYS days", code = "VALIDATION_FAILED")
                    call.respond(HttpStatusCode.OK, ledger.report(from, to))
                }.describe {
                    summary = "AI usage and cost (admin)"
                    description = "Model calls (validation retries and failures included) with token counts and USD cost, " +
                            "by day, use case, model and user. Days are UTC and inclusive; default the last $DEFAULT_DAYS days, at most $MAX_DAYS."
                    parameters {
                        query("from") { description = "First day, YYYY-MM-DD"; required = false }
                        query("to") { description = "Last day, YYYY-MM-DD (default today, UTC)"; required = false }
                    }
                    responses {
                        HttpStatusCode.OK { schema = jsonSchema<AiUsageResponse>() }
                        HttpStatusCode.BadRequest { schema = jsonSchema<ProblemDetail>() }
                        HttpStatusCode.Forbidden { schema = jsonSchema<ProblemDetail>() }
                    }
                }
            }
        }
    }
}

private const val DEFAULT_DAYS = 30L
private const val MAX_DAYS = 366L

private fun parseDay(value: String, name: String): LocalDate =
    runCatching { LocalDate.parse(value) }.getOrElse { throw BadRequestException("$name must be YYYY-MM-DD", code = "VALIDATION_FAILED") }
