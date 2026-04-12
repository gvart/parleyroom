package com.gvart.parleyroom.homework.routing

import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.homework.data.HomeworkStatus
import com.gvart.parleyroom.homework.service.HomeworkService
import com.gvart.parleyroom.homework.transfer.CreateHomeworkRequest
import com.gvart.parleyroom.homework.transfer.HomeworkResponse
import com.gvart.parleyroom.homework.transfer.ReviewHomeworkRequest
import com.gvart.parleyroom.homework.transfer.SubmitHomeworkRequest
import com.gvart.parleyroom.homework.transfer.UpdateHomeworkRequest
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

fun Application.configureHomeworkRouting() {
    val homeworkService: HomeworkService by dependencies

    routing {
        authenticate {
            route("/api/v1/homework") {
                get {
                    val principal = call.principal<UserPrincipal>()!!
                    val studentId = call.queryParameters["studentId"]?.let(UUID::fromString)
                    val status = call.queryParameters["status"]?.let { HomeworkStatus.valueOf(it) }

                    val result = homeworkService.getHomework(principal, studentId, status)
                    call.respond(HttpStatusCode.OK, result)
                }.describe {
                    summary = "Get homework"
                    description = "Lists homework. Students see their own, teachers see homework they assigned, admins see all."
                    parameters {
                        query("studentId") { description = "Filter by student UUID"; required = false }
                        query("status") { description = "Filter by status (OPEN, SUBMITTED, IN_REVIEW, DONE, REJECTED)"; required = false }
                    }
                    responses {
                        HttpStatusCode.OK {
                            description = "List of homework"
                            schema = jsonSchema<List<HomeworkResponse>>()
                        }
                    }
                }

                post<CreateHomeworkRequest> {
                    val principal = call.principal<UserPrincipal>()!!

                    val result = homeworkService.createHomework(it, principal)
                    call.respond(HttpStatusCode.Created, result)
                }.describe {
                    summary = "Create homework"
                    description = "Assigns homework to a student. Only teachers and admins can create."
                    requestBody { schema = jsonSchema<CreateHomeworkRequest>() }
                    responses {
                        HttpStatusCode.Created {
                            description = "Homework created"
                            schema = jsonSchema<HomeworkResponse>()
                        }
                        HttpStatusCode.Forbidden {
                            description = "Students cannot create homework"
                            schema = jsonSchema<ProblemDetail>()
                        }
                    }
                }

                route("/{id}") {
                    get {
                        val principal = call.principal<UserPrincipal>()!!
                        val id = UUID.fromString(call.parameters["id"])

                        val result = homeworkService.getHomeworkById(id, principal)
                        call.respond(HttpStatusCode.OK, result)
                    }.describe {
                        summary = "Get homework by ID"
                        description = "Gets a single homework by ID."
                        parameters { path("id") { description = "UUID of the homework" } }
                        responses {
                            HttpStatusCode.OK {
                                description = "Homework details"
                                schema = jsonSchema<HomeworkResponse>()
                            }
                            HttpStatusCode.NotFound {
                                description = "Homework not found"
                                schema = jsonSchema<ProblemDetail>()
                            }
                        }
                    }

                    put<UpdateHomeworkRequest> {
                        val principal = call.principal<UserPrincipal>()!!
                        val id = UUID.fromString(call.parameters["id"])

                        val result = homeworkService.updateHomework(id, it, principal)
                        call.respond(HttpStatusCode.OK, result)
                    }.describe {
                        summary = "Update homework"
                        description = "Updates homework details. Only the assigning teacher or admin."
                        requestBody { schema = jsonSchema<UpdateHomeworkRequest>() }
                        parameters { path("id") { description = "UUID of the homework" } }
                        responses {
                            HttpStatusCode.OK {
                                description = "Homework updated"
                                schema = jsonSchema<HomeworkResponse>()
                            }
                        }
                    }

                    delete {
                        val principal = call.principal<UserPrincipal>()!!
                        val id = UUID.fromString(call.parameters["id"])

                        homeworkService.deleteHomework(id, principal)
                        call.respond(HttpStatusCode.NoContent)
                    }.describe {
                        summary = "Delete homework"
                        description = "Deletes homework. Only the assigning teacher or admin."
                        parameters { path("id") { description = "UUID of the homework" } }
                        responses {
                            HttpStatusCode.NoContent { description = "Homework deleted" }
                        }
                    }

                    post<SubmitHomeworkRequest>("/submit") {
                        val principal = call.principal<UserPrincipal>()!!
                        val id = UUID.fromString(call.parameters["id"])

                        val result = homeworkService.submitHomework(id, it, principal)
                        call.respond(HttpStatusCode.OK, result)
                    }.describe {
                        summary = "Submit homework"
                        description = "Student submits their homework. Can re-submit if rejected."
                        requestBody { schema = jsonSchema<SubmitHomeworkRequest>() }
                        parameters { path("id") { description = "UUID of the homework" } }
                        responses {
                            HttpStatusCode.OK {
                                description = "Homework submitted"
                                schema = jsonSchema<HomeworkResponse>()
                            }
                            HttpStatusCode.BadRequest {
                                description = "Homework not in submittable state"
                                schema = jsonSchema<ProblemDetail>()
                            }
                        }
                    }

                    post<ReviewHomeworkRequest>("/review") {
                        val principal = call.principal<UserPrincipal>()!!
                        val id = UUID.fromString(call.parameters["id"])

                        val result = homeworkService.reviewHomework(id, it, principal)
                        call.respond(HttpStatusCode.OK, result)
                    }.describe {
                        summary = "Review homework"
                        description = "Teacher reviews submitted homework. Sets status to DONE or REJECTED with optional feedback."
                        requestBody { schema = jsonSchema<ReviewHomeworkRequest>() }
                        parameters { path("id") { description = "UUID of the homework" } }
                        responses {
                            HttpStatusCode.OK {
                                description = "Homework reviewed"
                                schema = jsonSchema<HomeworkResponse>()
                            }
                            HttpStatusCode.BadRequest {
                                description = "Homework not in reviewable state"
                                schema = jsonSchema<ProblemDetail>()
                            }
                        }
                    }
                }
            }
        }
    }
}
