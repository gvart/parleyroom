package com.gvart.parleyroom.lesson.routing

import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.lesson.service.LessonService
import com.gvart.parleyroom.lesson.transfer.CancelLessonRequest
import com.gvart.parleyroom.lesson.transfer.CompleteLessonRequest
import com.gvart.parleyroom.lesson.transfer.CreateLessonRequest
import com.gvart.parleyroom.lesson.transfer.LessonDocumentResponse
import com.gvart.parleyroom.lesson.transfer.LessonResponse
import com.gvart.parleyroom.lesson.transfer.ReflectLessonRequest
import com.gvart.parleyroom.lesson.transfer.RescheduleLessonRequest
import com.gvart.parleyroom.lesson.transfer.StartLessonResponse
import com.gvart.parleyroom.lesson.transfer.SyncLessonDocumentRequest
import com.gvart.parleyroom.user.security.UserPrincipal
import com.gvart.parleyroom.video.transfer.VideoAccess
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.time.OffsetDateTime
import java.util.UUID

fun Application.configureLessonRouting() {
    val lessonService: LessonService by dependencies

    routing {
        authenticate {
            route("/api/v1/lessons") {
                get {
                    val principal = call.principal<UserPrincipal>()!!
                    val from = call.queryParameters["from"]?.let(OffsetDateTime::parse)
                    val to = call.queryParameters["to"]?.let(OffsetDateTime::parse)

                    val result = lessonService.getLessons(principal, from, to)
                    call.respond(HttpStatusCode.OK, result)
                }.describe {
                    summary = "Get lessons"
                    description = "Retrieves lessons for the authenticated user. Teachers see their lessons, students see confirmed lessons they participate in, admins see all. Supports date range filtering via 'from' and 'to' query parameters (ISO 8601 with offset)."
                    parameters {
                        query("from") {
                            description = "Start of date range (ISO 8601, e.g. 2026-04-01T00:00:00+02:00)"
                            required = false
                        }
                        query("to") {
                            description = "End of date range (ISO 8601, e.g. 2026-04-30T23:59:59+02:00)"
                            required = false
                        }
                    }
                    responses {
                        HttpStatusCode.OK {
                            description = "List of lessons"
                            schema = jsonSchema<List<LessonResponse>>()
                        }
                        HttpStatusCode.Unauthorized {
                            description = "Missing or invalid authentication token"
                            schema = jsonSchema<ProblemDetail>()
                        }
                    }
                }

                post<CreateLessonRequest> {
                    val principal = call.principal<UserPrincipal>()!!

                    val result = lessonService.createLesson(it, principal)
                    call.respond(HttpStatusCode.Created, result)
                }.describe {
                    summary = "Create lesson"
                    description = "Creates a new lesson. Students can only create ONE_ON_ONE lessons; teachers and admins can create any type."
                    requestBody {
                        schema = jsonSchema<CreateLessonRequest>()
                    }
                    responses {
                        HttpStatusCode.Created {
                            description = "Lesson created successfully"
                            schema = jsonSchema<LessonResponse>()
                        }
                        HttpStatusCode.BadRequest {
                            description = "Invalid request body or validation error"
                            schema = jsonSchema<ProblemDetail>()
                        }
                        HttpStatusCode.Unauthorized {
                            description = "Missing or invalid authentication token"
                            schema = jsonSchema<ProblemDetail>()
                        }
                        HttpStatusCode.Forbidden {
                            description = "Insufficient permissions for the requested lesson type"
                            schema = jsonSchema<ProblemDetail>()
                        }
                    }
                }

                route("/{id}") {
                    get {
                        val principal = call.principal<UserPrincipal>()!!
                        val id = UUID.fromString(call.parameters["id"])

                        val result = lessonService.getLesson(id, principal)
                        call.respond(HttpStatusCode.OK, result)
                    }.describe {
                        summary = "Get lesson by ID"
                        description = "Retrieves a single lesson by its ID. Only participants (teacher, confirmed students) and admins can access it."
                        parameters {
                            path("id") {
                                description = "UUID of the lesson"
                            }
                        }
                        responses {
                            HttpStatusCode.OK {
                                description = "Lesson details"
                                schema = jsonSchema<LessonResponse>()
                            }
                            HttpStatusCode.Unauthorized {
                                description = "Missing or invalid authentication token"
                                schema = jsonSchema<ProblemDetail>()
                            }
                            HttpStatusCode.Forbidden {
                                description = "Not a participant of this lesson"
                                schema = jsonSchema<ProblemDetail>()
                            }
                            HttpStatusCode.NotFound {
                                description = "Lesson not found"
                                schema = jsonSchema<ProblemDetail>()
                            }
                        }
                    }

                    post("/accept") {
                        val principal = call.principal<UserPrincipal>()!!
                        val id = UUID.fromString(call.parameters["id"])

                        val result = lessonService.acceptLesson(id, principal)
                        call.respond(HttpStatusCode.OK, result)
                    }.describe {
                        summary = "Accept lesson"
                        description = "Accepts a pending lesson invitation."
                        parameters {
                            path("id") {
                                description = "UUID of the lesson"
                            }
                        }
                        responses {
                            HttpStatusCode.OK {
                                description = "Lesson accepted"
                                schema = jsonSchema<LessonResponse>()
                            }
                            HttpStatusCode.Unauthorized {
                                description = "Missing or invalid authentication token"
                                schema = jsonSchema<ProblemDetail>()
                            }
                            HttpStatusCode.NotFound {
                                description = "Lesson not found"
                                schema = jsonSchema<ProblemDetail>()
                            }
                        }
                    }

                    post<CancelLessonRequest>("/cancel") {
                        val principal = call.principal<UserPrincipal>()!!
                        val id = UUID.fromString(call.parameters["id"])

                        val result = lessonService.cancelLesson(id, it, principal)
                        call.respond(HttpStatusCode.OK, result)
                    }.describe {
                        summary = "Cancel lesson"
                        description = "Cancels a lesson. Any participant (teacher, student, or admin) can cancel. Cannot cancel completed or already cancelled lessons."
                        requestBody {
                            schema = jsonSchema<CancelLessonRequest>()
                        }
                        parameters {
                            path("id") {
                                description = "UUID of the lesson"
                            }
                        }
                        responses {
                            HttpStatusCode.OK {
                                description = "Lesson cancelled"
                                schema = jsonSchema<LessonResponse>()
                            }
                            HttpStatusCode.BadRequest {
                                description = "Lesson cannot be cancelled (already completed or cancelled)"
                                schema = jsonSchema<ProblemDetail>()
                            }
                            HttpStatusCode.Unauthorized {
                                description = "Missing or invalid authentication token"
                                schema = jsonSchema<ProblemDetail>()
                            }
                            HttpStatusCode.Forbidden {
                                description = "Not a participant of this lesson"
                                schema = jsonSchema<ProblemDetail>()
                            }
                            HttpStatusCode.NotFound {
                                description = "Lesson not found"
                                schema = jsonSchema<ProblemDetail>()
                            }
                        }
                    }

                    post("/join") {
                        val principal = call.principal<UserPrincipal>()!!
                        val id = UUID.fromString(call.parameters["id"])

                        lessonService.joinLesson(id, principal)
                        call.respond(HttpStatusCode.Created)
                    }.describe {
                        summary = "Request to join lesson"
                        description = "Requests to join a group lesson. Not allowed for ONE_ON_ONE lessons. Teacher must accept the request."
                        parameters {
                            path("id") {
                                description = "UUID of the lesson"
                            }
                        }
                        responses {
                            HttpStatusCode.Created {
                                description = "Join request submitted"
                            }
                            HttpStatusCode.BadRequest {
                                description = "Cannot join this lesson type or lesson is full"
                                schema = jsonSchema<ProblemDetail>()
                            }
                            HttpStatusCode.Unauthorized {
                                description = "Missing or invalid authentication token"
                                schema = jsonSchema<ProblemDetail>()
                            }
                            HttpStatusCode.Conflict {
                                description = "Already a participant or request already pending"
                                schema = jsonSchema<ProblemDetail>()
                            }
                        }
                    }

                    post("/start") {
                        val principal = call.principal<UserPrincipal>()!!
                        val id = UUID.fromString(call.parameters["id"])

                        val result = lessonService.startLesson(id, principal)
                        call.respond(HttpStatusCode.OK, result)
                    }.describe {
                        summary = "Start lesson"
                        description = "Starts a lesson: sets startedAt, marks confirmed students as attended, creates a lesson document, and returns a LiveKit room + access token for the teacher."
                        parameters {
                            path("id") {
                                description = "UUID of the lesson"
                            }
                        }
                        responses {
                            HttpStatusCode.OK {
                                description = "Lesson started, document created, and video room provisioned"
                                schema = jsonSchema<StartLessonResponse>()
                            }
                            HttpStatusCode.BadRequest {
                                description = "Lesson is not in CONFIRMED status"
                                schema = jsonSchema<ProblemDetail>()
                            }
                            HttpStatusCode.Forbidden {
                                description = "Only the assigned teacher or admin can start the lesson"
                                schema = jsonSchema<ProblemDetail>()
                            }
                            HttpStatusCode.Conflict {
                                description = "Lesson has already been started"
                                schema = jsonSchema<ProblemDetail>()
                            }
                            HttpStatusCode.NotFound {
                                description = "Lesson not found"
                                schema = jsonSchema<ProblemDetail>()
                            }
                        }
                    }

                    post("/video-token") {
                        val principal = call.principal<UserPrincipal>()!!
                        val id = UUID.fromString(call.parameters["id"])

                        val result = lessonService.getVideoAccess(id, principal)
                        call.respond(HttpStatusCode.OK, result)
                    }.describe {
                        summary = "Get video room access token"
                        description = "Returns a LiveKit access token for the authenticated participant to join the lesson's video room. Lesson must be IN_PROGRESS."
                        parameters {
                            path("id") {
                                description = "UUID of the lesson"
                            }
                        }
                        responses {
                            HttpStatusCode.OK {
                                description = "Access token issued"
                                schema = jsonSchema<VideoAccess>()
                            }
                            HttpStatusCode.BadRequest {
                                description = "Lesson is not in progress"
                                schema = jsonSchema<ProblemDetail>()
                            }
                            HttpStatusCode.Forbidden {
                                description = "Not a participant of this lesson"
                                schema = jsonSchema<ProblemDetail>()
                            }
                            HttpStatusCode.NotFound {
                                description = "Lesson not found"
                                schema = jsonSchema<ProblemDetail>()
                            }
                        }
                    }

                    put<SyncLessonDocumentRequest>("/sync") {
                        val principal = call.principal<UserPrincipal>()!!
                        val id = UUID.fromString(call.parameters["id"])

                        val result = lessonService.syncDocument(id, it, principal)
                        call.respond(HttpStatusCode.OK, result)
                    }.describe {
                        summary = "Sync lesson document"
                        description = "Syncs lesson notes. Teachers update teacherNotes, students update studentNotes."
                        requestBody {
                            schema = jsonSchema<SyncLessonDocumentRequest>()
                        }
                        parameters {
                            path("id") {
                                description = "UUID of the lesson"
                            }
                        }
                        responses {
                            HttpStatusCode.OK {
                                description = "Document synced"
                                schema = jsonSchema<LessonDocumentResponse>()
                            }
                            HttpStatusCode.BadRequest {
                                description = "Lesson has not been started yet"
                                schema = jsonSchema<ProblemDetail>()
                            }
                            HttpStatusCode.Forbidden {
                                description = "Not a participant of this lesson"
                                schema = jsonSchema<ProblemDetail>()
                            }
                            HttpStatusCode.NotFound {
                                description = "Lesson not found"
                                schema = jsonSchema<ProblemDetail>()
                            }
                        }
                    }

                    post<CompleteLessonRequest>("/complete") {
                        val principal = call.principal<UserPrincipal>()!!
                        val id = UUID.fromString(call.parameters["id"])

                        val result = lessonService.completeLesson(id, it, principal)
                        call.respond(HttpStatusCode.OK, result)
                    }.describe {
                        summary = "Complete lesson"
                        description = "Completes a lesson. Teachers/admins submit teacherNotes, teacherWentWell, teacherWorkingOn. Students can submit reflections via the sync endpoint before completion."
                        requestBody {
                            schema = jsonSchema<CompleteLessonRequest>()
                        }
                        parameters {
                            path("id") {
                                description = "UUID of the lesson"
                            }
                        }
                        responses {
                            HttpStatusCode.OK {
                                description = "Lesson completed"
                                schema = jsonSchema<LessonDocumentResponse>()
                            }
                            HttpStatusCode.BadRequest {
                                description = "Lesson not started or already completed"
                                schema = jsonSchema<ProblemDetail>()
                            }
                            HttpStatusCode.Forbidden {
                                description = "Not a participant of this lesson"
                                schema = jsonSchema<ProblemDetail>()
                            }
                            HttpStatusCode.NotFound {
                                description = "Lesson not found"
                                schema = jsonSchema<ProblemDetail>()
                            }
                        }
                    }

                    post<ReflectLessonRequest>("/reflect") {
                        val principal = call.principal<UserPrincipal>()!!
                        val id = UUID.fromString(call.parameters["id"])

                        val result = lessonService.reflectOnLesson(id, it, principal)
                        call.respond(HttpStatusCode.OK, result)
                    }.describe {
                        summary = "Submit student reflection"
                        description = "Allows a student to submit their reflection and what was hard today. Can be called anytime after the lesson is started."
                        requestBody {
                            schema = jsonSchema<ReflectLessonRequest>()
                        }
                        parameters {
                            path("id") {
                                description = "UUID of the lesson"
                            }
                        }
                        responses {
                            HttpStatusCode.OK {
                                description = "Reflection saved"
                                schema = jsonSchema<LessonDocumentResponse>()
                            }
                            HttpStatusCode.BadRequest {
                                description = "Lesson has not been started yet"
                                schema = jsonSchema<ProblemDetail>()
                            }
                            HttpStatusCode.Forbidden {
                                description = "Only students can submit reflections"
                                schema = jsonSchema<ProblemDetail>()
                            }
                            HttpStatusCode.NotFound {
                                description = "Lesson not found"
                                schema = jsonSchema<ProblemDetail>()
                            }
                        }
                    }

                    route("/participants/{studentId}") {
                        post("/accept") {
                            val principal = call.principal<UserPrincipal>()!!
                            val id = UUID.fromString(call.parameters["id"])
                            val studentId = UUID.fromString(call.parameters["studentId"])

                            lessonService.acceptJoinRequest(id, studentId, principal)
                            call.respond(HttpStatusCode.OK)
                        }.describe {
                            summary = "Accept join request"
                            description = "Accepts a student's request to join the lesson. Only the teacher can perform this action."
                            parameters {
                                path("id") {
                                    description = "UUID of the lesson"
                                }
                                path("studentId") {
                                    description = "UUID of the student"
                                }
                            }
                            responses {
                                HttpStatusCode.OK {
                                    description = "Join request accepted"
                                }
                                HttpStatusCode.Unauthorized {
                                    description = "Missing or invalid authentication token"
                                    schema = jsonSchema<ProblemDetail>()
                                }
                                HttpStatusCode.Forbidden {
                                    description = "Only the teacher can accept join requests"
                                    schema = jsonSchema<ProblemDetail>()
                                }
                                HttpStatusCode.NotFound {
                                    description = "Lesson or student not found"
                                    schema = jsonSchema<ProblemDetail>()
                                }
                            }
                        }

                        post("/reject") {
                            val principal = call.principal<UserPrincipal>()!!
                            val id = UUID.fromString(call.parameters["id"])
                            val studentId = UUID.fromString(call.parameters["studentId"])

                            lessonService.rejectJoinRequest(id, studentId, principal)
                            call.respond(HttpStatusCode.OK)
                        }.describe {
                            summary = "Reject join request"
                            description = "Rejects a student's request to join the lesson. Only the teacher can perform this action."
                            parameters {
                                path("id") {
                                    description = "UUID of the lesson"
                                }
                                path("studentId") {
                                    description = "UUID of the student"
                                }
                            }
                            responses {
                                HttpStatusCode.OK {
                                    description = "Join request rejected"
                                }
                                HttpStatusCode.Unauthorized {
                                    description = "Missing or invalid authentication token"
                                    schema = jsonSchema<ProblemDetail>()
                                }
                                HttpStatusCode.Forbidden {
                                    description = "Only the teacher can reject join requests"
                                    schema = jsonSchema<ProblemDetail>()
                                }
                                HttpStatusCode.NotFound {
                                    description = "Lesson or student not found"
                                    schema = jsonSchema<ProblemDetail>()
                                }
                            }
                        }
                    }

                    route("/reschedule") {
                        post<RescheduleLessonRequest> {
                            val principal = call.principal<UserPrincipal>()!!
                            val id = UUID.fromString(call.parameters["id"])

                            lessonService.rescheduleLesson(id, it, principal)
                            call.respond(HttpStatusCode.Created)
                        }.describe {
                            summary = "Request lesson reschedule"
                            description = "Requests to reschedule a lesson to a new time."
                            requestBody {
                                schema = jsonSchema<RescheduleLessonRequest>()
                            }
                            parameters {
                                path("id") {
                                    description = "UUID of the lesson"
                                }
                            }
                            responses {
                                HttpStatusCode.Created {
                                    description = "Reschedule request created"
                                }
                                HttpStatusCode.BadRequest {
                                    description = "Invalid request body or validation error"
                                    schema = jsonSchema<ProblemDetail>()
                                }
                                HttpStatusCode.Unauthorized {
                                    description = "Missing or invalid authentication token"
                                    schema = jsonSchema<ProblemDetail>()
                                }
                                HttpStatusCode.NotFound {
                                    description = "Lesson not found"
                                    schema = jsonSchema<ProblemDetail>()
                                }
                            }
                        }

                        post("/accept") {
                            val principal = call.principal<UserPrincipal>()!!
                            val id = UUID.fromString(call.parameters["id"])

                            val result = lessonService.acceptReschedule(id, principal)
                            call.respond(HttpStatusCode.OK, result)
                        }.describe {
                            summary = "Accept reschedule"
                            description = "Accepts a pending reschedule request."
                            parameters {
                                path("id") {
                                    description = "UUID of the lesson"
                                }
                            }
                            responses {
                                HttpStatusCode.OK {
                                    description = "Reschedule accepted"
                                    schema = jsonSchema<LessonResponse>()
                                }
                                HttpStatusCode.Unauthorized {
                                    description = "Missing or invalid authentication token"
                                    schema = jsonSchema<ProblemDetail>()
                                }
                                HttpStatusCode.NotFound {
                                    description = "Lesson not found"
                                    schema = jsonSchema<ProblemDetail>()
                                }
                            }
                        }

                        post("/reject") {
                            val principal = call.principal<UserPrincipal>()!!
                            val id = UUID.fromString(call.parameters["id"])

                            lessonService.rejectReschedule(id, principal)
                            call.respond(HttpStatusCode.OK)
                        }.describe {
                            summary = "Reject reschedule"
                            description = "Rejects a pending reschedule request."
                            parameters {
                                path("id") {
                                    description = "UUID of the lesson"
                                }
                            }
                            responses {
                                HttpStatusCode.OK {
                                    description = "Reschedule rejected"
                                }
                                HttpStatusCode.Unauthorized {
                                    description = "Missing or invalid authentication token"
                                    schema = jsonSchema<ProblemDetail>()
                                }
                                HttpStatusCode.NotFound {
                                    description = "Lesson not found"
                                    schema = jsonSchema<ProblemDetail>()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}