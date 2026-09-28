package com.gvart.parleyroom.activity.routing

import com.gvart.parleyroom.activity.service.StreakService
import com.gvart.parleyroom.activity.transfer.StreakResponse
import com.gvart.parleyroom.common.routing.requirePrincipal
import com.gvart.parleyroom.common.transfer.ProblemDetail
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureActivityRouting() {
    val streakService: StreakService by dependencies

    routing {
        authenticate {
            route("/api/v1/users") {
                get("/me/streak") {
                    val principal = call.requirePrincipal()

                    val result = streakService.getStreak(principal.id, principal)
                    call.respond(HttpStatusCode.OK, result)
                }.describe {
                    summary = "Get current user's learning streak"
                    description = "Returns the daily learning streak of the authenticated user. A day (in the user's timezone) is active when the user reviewed a vocabulary word, completed a lesson, or submitted homework. The current streak still counts when only yesterday is active (today isn't over yet). `week` holds the current ISO week, Monday to Sunday."
                    responses {
                        HttpStatusCode.OK {
                            description = "Learning streak"
                            schema = jsonSchema<StreakResponse>()
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
}
