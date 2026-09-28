package com.gvart.parleyroom.goal.routing

import com.gvart.parleyroom.common.routing.getPathUUID
import com.gvart.parleyroom.common.routing.getQueryUUID
import com.gvart.parleyroom.common.routing.requirePrincipal
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.goal.data.GoalStatus
import com.gvart.parleyroom.goal.service.GoalService
import com.gvart.parleyroom.goal.transfer.GoalInput
import com.gvart.parleyroom.goal.transfer.GoalPatch
import com.gvart.parleyroom.goal.transfer.GoalResponse
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureGoalRouting() {
    val goalService: GoalService by dependencies

    routing {
        authenticate {
            route("/api/v1/goals") {
                get {
                    val statuses = call.request.queryParameters["status"]?.split(',')?.map { raw ->
                        GoalStatus.entries.firstOrNull { it.name == raw.trim() } ?: throw BadRequestException("Unknown goal status: $raw")
                    }
                    val result = goalService.list(call.requirePrincipal(), call.getQueryUUID("studentId"), statuses)
                    call.respond(HttpStatusCode.OK, result)
                }.describe {
                    summary = "List goals"
                    description = "Auto-tracked goals with computed progress. Students: own goals; teachers: goals they set; admins: all. " +
                            "ACTIVE first, then targetDate asc (nulls last), createdAt desc."
                    parameters {
                        query("studentId") { description = "Filter by student UUID"; required = false }
                        query("status") { description = "Comma list of ACTIVE, ACHIEVED, ARCHIVED"; required = false }
                    }
                    responses { HttpStatusCode.OK { schema = jsonSchema<List<GoalResponse>>() } }
                }

                post<GoalInput> {
                    val result = goalService.create(it, call.requirePrincipal())
                    call.respond(HttpStatusCode.Created, result)
                }.describe {
                    summary = "Create goal"
                    description = "EXAM (examName + targetDate required) or LEVEL goal for a linked student. Teacher only. " +
                            "Stores the current progress as baselinePercent."
                    requestBody { schema = jsonSchema<GoalInput>() }
                    responses {
                        HttpStatusCode.Created { schema = jsonSchema<GoalResponse>() }
                        HttpStatusCode.BadRequest { description = "GOAL_INVALID"; schema = jsonSchema<ProblemDetail>() }
                    }
                }

                route("/{id}") {
                    get {
                        call.respond(HttpStatusCode.OK, goalService.get(call.getPathUUID(), call.requirePrincipal()))
                    }.describe {
                        summary = "Get goal"
                        parameters { path("id") { description = "Goal UUID" } }
                        responses {
                            HttpStatusCode.OK { schema = jsonSchema<GoalResponse>() }
                            HttpStatusCode.NotFound { description = "GOAL_NOT_FOUND"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    patch<GoalPatch> {
                        call.respond(HttpStatusCode.OK, goalService.patch(call.getPathUUID(), it, call.requirePrincipal()))
                    }.describe {
                        summary = "Update goal"
                        description = "Edit exam name, target level/date, note or status (ACTIVE, ACHIEVED, ARCHIVED). The goal's teacher only."
                        requestBody { schema = jsonSchema<GoalPatch>() }
                        parameters { path("id") { description = "Goal UUID" } }
                        responses {
                            HttpStatusCode.OK { schema = jsonSchema<GoalResponse>() }
                            HttpStatusCode.BadRequest { description = "GOAL_INVALID"; schema = jsonSchema<ProblemDetail>() }
                            HttpStatusCode.NotFound { description = "GOAL_NOT_FOUND"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    delete {
                        goalService.delete(call.getPathUUID(), call.requirePrincipal())
                        call.respond(HttpStatusCode.NoContent)
                    }.describe {
                        summary = "Delete goal"
                        description = "The goal's teacher or an admin."
                        parameters { path("id") { description = "Goal UUID" } }
                        responses { HttpStatusCode.NoContent { description = "Deleted" } }
                    }
                }
            }
        }
    }
}
