package com.gvart.parleyroom.goal.routing

import com.gvart.parleyroom.common.transfer.PageRequest
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.goal.data.GoalStatus
import com.gvart.parleyroom.goal.service.GoalService
import com.gvart.parleyroom.goal.transfer.CreateGoalRequest
import com.gvart.parleyroom.goal.transfer.GoalPageResponse
import com.gvart.parleyroom.goal.transfer.GoalResponse
import com.gvart.parleyroom.goal.transfer.UpdateGoalProgressRequest
import com.gvart.parleyroom.goal.transfer.UpdateGoalRequest
import com.gvart.parleyroom.user.security.UserPrincipal
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.util.UUID

fun Application.configureGoalRouting() {
    val goalService: GoalService by dependencies

    routing {
        authenticate {
            route("/api/v1/goals") {
                get {
                    val principal = call.principal<UserPrincipal>()!!
                    val studentId = call.request.queryParameters["studentId"]?.let(UUID::fromString)
                    val status = call.request.queryParameters["status"]?.let { GoalStatus.valueOf(it) }

                    val result = goalService.getGoals(principal, studentId, status, PageRequest.from(call))
                    call.respond(HttpStatusCode.OK, result)
                }.describe {
                    summary = "Get learning goals"
                    description = "Lists learning goals with pagination. Students see their own, teachers see their students', admins see all."
                    parameters {
                        query("studentId") { description = "Filter by student UUID"; required = false }
                        query("status") { description = "Filter by status (ACTIVE, COMPLETED, ABANDONED)"; required = false }
                        query("page") { description = "Page number (1-based, default 1)"; required = false }
                        query("pageSize") { description = "Items per page (default 20, max 100)"; required = false }
                    }
                    responses {
                        HttpStatusCode.OK {
                            description = "Paginated list of goals"
                            schema = jsonSchema<GoalPageResponse>()
                        }
                    }
                }

                post<CreateGoalRequest> {
                    val principal = call.principal<UserPrincipal>()!!

                    val result = goalService.createGoal(it, principal)
                    call.respond(HttpStatusCode.Created, result)
                }.describe {
                    summary = "Create learning goal"
                    description = "Creates a learning goal. Students create for themselves, teachers for their students."
                    requestBody { schema = jsonSchema<CreateGoalRequest>() }
                    responses {
                        HttpStatusCode.Created {
                            description = "Goal created"
                            schema = jsonSchema<GoalResponse>()
                        }
                    }
                }

                route("/{id}") {
                    get {
                        val principal = call.principal<UserPrincipal>()!!
                        val id = UUID.fromString(call.parameters["id"])

                        val result = goalService.getGoal(id, principal)
                        call.respond(HttpStatusCode.OK, result)
                    }.describe {
                        summary = "Get learning goal"
                        description = "Gets a single learning goal by ID."
                        parameters { path("id") { description = "UUID of the goal" } }
                        responses {
                            HttpStatusCode.OK {
                                description = "Goal details"
                                schema = jsonSchema<GoalResponse>()
                            }
                            HttpStatusCode.NotFound {
                                description = "Goal not found"
                                schema = jsonSchema<ProblemDetail>()
                            }
                        }
                    }

                    put<UpdateGoalRequest> {
                        val principal = call.principal<UserPrincipal>()!!
                        val id = UUID.fromString(call.parameters["id"])

                        val result = goalService.updateGoal(id, it, principal)
                        call.respond(HttpStatusCode.OK, result)
                    }.describe {
                        summary = "Update learning goal"
                        description = "Updates goal description or target date."
                        requestBody { schema = jsonSchema<UpdateGoalRequest>() }
                        parameters { path("id") { description = "UUID of the goal" } }
                        responses {
                            HttpStatusCode.OK {
                                description = "Goal updated"
                                schema = jsonSchema<GoalResponse>()
                            }
                        }
                    }

                    delete {
                        val principal = call.principal<UserPrincipal>()!!
                        val id = UUID.fromString(call.parameters["id"])

                        goalService.deleteGoal(id, principal)
                        call.respond(HttpStatusCode.NoContent)
                    }.describe {
                        summary = "Delete learning goal"
                        description = "Deletes a learning goal."
                        parameters { path("id") { description = "UUID of the goal" } }
                        responses {
                            HttpStatusCode.NoContent { description = "Goal deleted" }
                        }
                    }

                    put<UpdateGoalProgressRequest>("/progress") {
                        val principal = call.principal<UserPrincipal>()!!
                        val id = UUID.fromString(call.parameters["id"])

                        val result = goalService.updateProgress(id, it, principal)
                        call.respond(HttpStatusCode.OK, result)
                    }.describe {
                        summary = "Update goal progress"
                        description = "Updates goal progress (0-100)."
                        requestBody { schema = jsonSchema<UpdateGoalProgressRequest>() }
                        parameters { path("id") { description = "UUID of the goal" } }
                        responses {
                            HttpStatusCode.OK {
                                description = "Progress updated"
                                schema = jsonSchema<GoalResponse>()
                            }
                        }
                    }

                    post("/complete") {
                        val principal = call.principal<UserPrincipal>()!!
                        val id = UUID.fromString(call.parameters["id"])

                        val result = goalService.completeGoal(id, principal)
                        call.respond(HttpStatusCode.OK, result)
                    }.describe {
                        summary = "Complete goal"
                        description = "Marks goal as completed and sets progress to 100."
                        parameters { path("id") { description = "UUID of the goal" } }
                        responses {
                            HttpStatusCode.OK {
                                description = "Goal completed"
                                schema = jsonSchema<GoalResponse>()
                            }
                            HttpStatusCode.BadRequest {
                                description = "Goal is not active"
                                schema = jsonSchema<ProblemDetail>()
                            }
                        }
                    }

                    post("/abandon") {
                        val principal = call.principal<UserPrincipal>()!!
                        val id = UUID.fromString(call.parameters["id"])

                        val result = goalService.abandonGoal(id, principal)
                        call.respond(HttpStatusCode.OK, result)
                    }.describe {
                        summary = "Abandon goal"
                        description = "Marks goal as abandoned."
                        parameters { path("id") { description = "UUID of the goal" } }
                        responses {
                            HttpStatusCode.OK {
                                description = "Goal abandoned"
                                schema = jsonSchema<GoalResponse>()
                            }
                            HttpStatusCode.BadRequest {
                                description = "Goal is not active"
                                schema = jsonSchema<ProblemDetail>()
                            }
                        }
                    }
                }
            }
        }
    }
}
