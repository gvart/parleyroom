package com.gvart.parleyroom.lesson.routing


import com.gvart.parleyroom.common.routing.getPathUUID
import com.gvart.parleyroom.common.routing.requirePrincipal
import com.gvart.parleyroom.common.transfer.PageRequest
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.lesson.service.LessonContentService
import com.gvart.parleyroom.lesson.service.LessonLifecycleService
import com.gvart.parleyroom.lesson.service.LessonParticipantService
import com.gvart.parleyroom.lesson.service.LessonRescheduleService
import com.gvart.parleyroom.lesson.service.LessonService
import com.gvart.parleyroom.lesson.transfer.CancelLessonRequest
import com.gvart.parleyroom.lesson.transfer.CreateLessonRequest
import com.gvart.parleyroom.lesson.transfer.JoinLessonResponse
import com.gvart.parleyroom.lesson.transfer.LessonPageResponse
import com.gvart.parleyroom.lesson.transfer.LessonResponse
import com.gvart.parleyroom.lesson.transfer.MoveLessonRequest
import com.gvart.parleyroom.lesson.transfer.MoveLessonResponse
import com.gvart.parleyroom.lesson.transfer.OpenClubResponse
import com.gvart.parleyroom.lesson.transfer.PublicCalendarResponse
import com.gvart.parleyroom.lesson.transfer.RescheduleLessonRequest
import com.gvart.parleyroom.lesson.transfer.StartLessonResponse
import com.gvart.parleyroom.lesson.transfer.UpdateLessonContentRequest
import com.gvart.parleyroom.vocabulary.transfer.VocabDisplaySetting
import com.gvart.parleyroom.video.transfer.VideoAccess
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
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.time.OffsetDateTime
import java.util.UUID

private const val RANGE_MAX_PAGE_SIZE = 500

fun Application.configureLessonRouting() {
    val lessonService: LessonService by dependencies
    val lifecycleService: LessonLifecycleService by dependencies
    val participantService: LessonParticipantService by dependencies
    val rescheduleService: LessonRescheduleService by dependencies
    val contentService: LessonContentService by dependencies

    routing {
        // Public (no auth) teacher calendar — used by the portal's shareable
        // /teachers/{id}/schedule page. 1:1 slots are scrubbed to busy blocks.
        route("/api/v1/public/teachers/{teacherId}/calendar") {
            get {
                val teacherId = call.getPathUUID("teacherId")
                val from = call.request.queryParameters["from"]?.let(OffsetDateTime::parse)
                val to = call.request.queryParameters["to"]?.let(OffsetDateTime::parse)
                val result = lessonService.getPublicCalendar(teacherId, from, to)
                call.respond(HttpStatusCode.OK, result)
            }.describe {
                summary = "Public teacher calendar"
                description = "Returns a teacher's non-cancelled lessons with private fields scrubbed. No authentication required. 1:1 lessons are returned as opaque busy blocks (title/topic null); group clubs keep their title, topic, level, and spot count so anyone can browse what's on the schedule."
                parameters {
                    path("teacherId") { description = "UUID of the teacher" }
                    query("from") {
                        description = "Start of date range (ISO 8601)"
                        required = false
                    }
                    query("to") {
                        description = "End of date range (ISO 8601)"
                        required = false
                    }
                }
                responses {
                    HttpStatusCode.OK {
                        description = "Scrubbed calendar payload"
                        schema = jsonSchema<PublicCalendarResponse>()
                    }
                    HttpStatusCode.NotFound {
                        description = "No teacher with that id"
                        schema = jsonSchema<ProblemDetail>()
                    }
                }
            }
        }

        authenticate {
            route("/api/v1/lessons") {
                get {
                    val principal = call.requirePrincipal()
                    val from = call.request.queryParameters["from"]?.let(OffsetDateTime::parse)
                    val to = call.request.queryParameters["to"]?.let(OffsetDateTime::parse)

                    // A bounded range is how the calendar loads a visible period in one request.
                    val maxPageSize = if (from != null && to != null) RANGE_MAX_PAGE_SIZE else PageRequest.MAX_PAGE_SIZE

                    val result = lessonService.getLessons(principal, from, to, PageRequest.from(call, maxPageSize))
                    call.respond(HttpStatusCode.OK, result)
                }.describe {
                    summary = "Get lessons"
                    description = "Retrieves a paginated list of lessons for the authenticated user. Teachers see their lessons, students see confirmed lessons they participate in, admins see all. Supports date range filtering via 'from' and 'to' query parameters (ISO 8601 with offset; scheduledAt within [from, to]). Always ordered by scheduledAt ascending, then id. When both 'from' and 'to' are given, pageSize may go up to 500."
                    parameters {
                        query("from") {
                            description = "Start of date range (ISO 8601, e.g. 2026-04-01T00:00:00+02:00)"
                            required = false
                        }
                        query("to") {
                            description = "End of date range (ISO 8601, e.g. 2026-04-30T23:59:59+02:00)"
                            required = false
                        }
                        query("page") {
                            description = "Page number (1-based, default 1)"
                            required = false
                        }
                        query("pageSize") {
                            description = "Number of lessons per page (default 20, max 100; max 500 when both from and to are given)"
                            required = false
                        }
                    }
                    responses {
                        HttpStatusCode.OK {
                            description = "Paginated list of lessons"
                            schema = jsonSchema<LessonPageResponse>()
                        }
                        HttpStatusCode.Unauthorized {
                            description = "Missing or invalid authentication token"
                            schema = jsonSchema<ProblemDetail>()
                        }
                    }
                }

                post<CreateLessonRequest> {
                    val principal = call.requirePrincipal()

                    val result = lifecycleService.createLesson(it, principal)
                    call.respond(HttpStatusCode.Created, result)
                }.describe {
                    summary = "Create lesson"
                    description = "Creates a new lesson. Students can only create ONE_ON_ONE lessons, as a REQUEST (or CONFIRMED when the teacher has autoConfirmBookings on); teachers and admins can create any type."
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

                get("/open-clubs") {
                    val principal = call.requirePrincipal()
                    call.respond(HttpStatusCode.OK, lessonService.getOpenClubs(principal))
                }.describe {
                    summary = "List open clubs"
                    description = "Student-only. Upcoming confirmed speaking/reading clubs of the student's actively linked teachers, ordered by start. Each club carries taken spots (confirmed + pending requests) and the viewer's own status; other participants are never named."
                    responses {
                        HttpStatusCode.OK {
                            description = "Upcoming clubs"
                            schema = jsonSchema<List<OpenClubResponse>>()
                        }
                        HttpStatusCode.Unauthorized {
                            description = "Missing or invalid authentication token"
                            schema = jsonSchema<ProblemDetail>()
                        }
                        HttpStatusCode.Forbidden {
                            description = "Caller is not a student"
                            schema = jsonSchema<ProblemDetail>()
                        }
                    }
                }

                route("/{id}") {
                    get {
                        val principal = call.requirePrincipal()
                        val id = call.getPathUUID()

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
                        val principal = call.requirePrincipal()
                        val id = call.getPathUUID()

                        val result = lifecycleService.acceptLesson(id, principal)
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
                        val principal = call.requirePrincipal()
                        val id = call.getPathUUID()

                        val result = lifecycleService.cancelLesson(id, it, principal)
                        call.respond(HttpStatusCode.OK, result)
                    }.describe {
                        summary = "Cancel lesson"
                        description = "Cancels a lesson. Any participant (teacher, student, or admin) can cancel. Cannot cancel completed or already cancelled lessons. The optional reason is stored and returned as cancelReason (with cancelledBy, cancelledAt). The other participants' LESSON_CANCELLED notification is held back for the 10-second un-cancel window."
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

                    post("/uncancel") {
                        val principal = call.requirePrincipal()
                        val id = call.getPathUUID()

                        val result = lifecycleService.uncancelLesson(id, principal)
                        call.respond(HttpStatusCode.OK, result)
                    }.describe {
                        summary = "Undo lesson cancellation"
                        description = "Restores a cancelled lesson to the status it had before. Only whoever cancelled it, within 10 seconds of cancelling. The held-back LESSON_CANCELLED notifications are dropped."
                        parameters {
                            path("id") {
                                description = "UUID of the lesson"
                            }
                        }
                        responses {
                            HttpStatusCode.OK {
                                description = "Lesson restored"
                                schema = jsonSchema<LessonResponse>()
                            }
                            HttpStatusCode.BadRequest {
                                description = "LESSON_INVALID_STATE: lesson is not cancelled"
                                schema = jsonSchema<ProblemDetail>()
                            }
                            HttpStatusCode.Forbidden {
                                description = "Caller did not cancel this lesson"
                                schema = jsonSchema<ProblemDetail>()
                            }
                            HttpStatusCode.NotFound {
                                description = "Lesson not found"
                                schema = jsonSchema<ProblemDetail>()
                            }
                            HttpStatusCode.Conflict {
                                description = "UNCANCEL_WINDOW_EXPIRED, or AVAILABILITY_OVERLAP if the slot was booked meanwhile"
                                schema = jsonSchema<ProblemDetail>()
                            }
                        }
                    }

                    post("/join") {
                        val principal = call.requirePrincipal()
                        val id = call.getPathUUID()

                        call.respond(HttpStatusCode.Created, participantService.joinLesson(id, principal))
                    }.describe {
                        summary = "Request to join lesson"
                        description = "Requests to join an upcoming club of a teacher the student is actively linked to. A pending request holds a spot until the teacher accepts or rejects it, or the student withdraws. When the teacher has autoAcceptClubJoins on, a join with a free spot is confirmed straight away. Not allowed for ONE_ON_ONE lessons."
                        parameters {
                            path("id") {
                                description = "UUID of the lesson"
                            }
                        }
                        responses {
                            HttpStatusCode.Created {
                                description = "Join request submitted, or accepted straight away"
                                schema = jsonSchema<JoinLessonResponse>()
                            }
                            HttpStatusCode.BadRequest {
                                description = "Cannot join this lesson type, or the lesson is not upcoming and confirmed"
                                schema = jsonSchema<ProblemDetail>()
                            }
                            HttpStatusCode.Unauthorized {
                                description = "Missing or invalid authentication token"
                                schema = jsonSchema<ProblemDetail>()
                            }
                            HttpStatusCode.Forbidden {
                                description = "NOT_TEACHERS_STUDENT: caller is not actively linked to the club's teacher"
                                schema = jsonSchema<ProblemDetail>()
                            }
                            HttpStatusCode.Conflict {
                                description = "ALREADY_PARTICIPANT, JOIN_REQUEST_ALREADY_PENDING, or CLUB_FULL"
                                schema = jsonSchema<ProblemDetail>()
                            }
                        }
                    }

                    delete("/join") {
                        val principal = call.requirePrincipal()
                        val id = call.getPathUUID()

                        participantService.withdrawJoinRequest(id, principal)
                        call.respond(HttpStatusCode.NoContent)
                    }.describe {
                        summary = "Withdraw join request"
                        description = "Withdraws the caller's pending join request, freeing the spot it held."
                        parameters {
                            path("id") {
                                description = "UUID of the lesson"
                            }
                        }
                        responses {
                            HttpStatusCode.NoContent {
                                description = "Request withdrawn"
                            }
                            HttpStatusCode.Unauthorized {
                                description = "Missing or invalid authentication token"
                                schema = jsonSchema<ProblemDetail>()
                            }
                            HttpStatusCode.NotFound {
                                description = "Lesson not found, or JOIN_REQUEST_NOT_FOUND when nothing is pending"
                                schema = jsonSchema<ProblemDetail>()
                            }
                        }
                    }

                    post("/start") {
                        val principal = call.requirePrincipal()
                        val id = call.getPathUUID()

                        val result = lifecycleService.startLesson(id, principal)
                        call.respond(HttpStatusCode.OK, result)
                    }.describe {
                        summary = "Start lesson"
                        description = "Starts a lesson: sets startedAt, marks confirmed students as attended, and returns a LiveKit room + access token for the teacher."
                        parameters {
                            path("id") {
                                description = "UUID of the lesson"
                            }
                        }
                        responses {
                            HttpStatusCode.OK {
                                description = "Lesson started and video room provisioned"
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
                        val principal = call.requirePrincipal()
                        val id = call.getPathUUID()

                        val result = lifecycleService.getVideoAccess(id, principal)
                        call.respond(HttpStatusCode.OK, result)
                    }.describe {
                        summary = "Get video room access token"
                        description = "Returns a LiveKit access token for the authenticated participant to join the lesson's video room. Teachers/admins can request a token while the lesson is CONFIRMED or IN_PROGRESS; students can request one while IN_PROGRESS or within 10 minutes of scheduledAt."
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
                                description = "Video room not yet available (outside early-join window or lesson not confirmed)"
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

                    patch<UpdateLessonContentRequest>("/content") {
                        val result = contentService.updateContent(call.getPathUUID(), it, call.requirePrincipal())
                        call.respond(HttpStatusCode.OK, result)
                    }.describe {
                        summary = "Update lesson content"
                        description = "Lesson teacher or admin, in any status. Sets rawNotes (the one plain-text lesson note; the live classroom autosaves it here), promptUsed, group link, and replaces topic, grammar topic, vocab entry and corrected-sentence lists (null = unchanged). rawNotes/promptUsed are never returned to students."
                        requestBody { schema = jsonSchema<UpdateLessonContentRequest>() }
                        parameters { path("id") { description = "UUID of the lesson" } }
                        responses {
                            HttpStatusCode.OK { schema = jsonSchema<LessonResponse>() }
                            HttpStatusCode.NotFound {
                                description = "LESSON_NOT_FOUND, TOPIC_NOT_FOUND, GRAMMAR_TOPIC_NOT_FOUND, VOCAB_ENTRY_NOT_FOUND, GROUP_NOT_FOUND"
                                schema = jsonSchema<ProblemDetail>()
                            }
                        }
                    }

                    route("/vocab-display") {
                        put<VocabDisplaySetting> {
                            val result = contentService.setVocabDisplay(call.getPathUUID(), it, call.requirePrincipal())
                            call.respond(HttpStatusCode.OK, result)
                        }.describe {
                            summary = "Override vocab display for a lesson"
                            description = "Words students received in this lesson use this setting instead of the teacher–student one."
                            requestBody { schema = jsonSchema<VocabDisplaySetting>() }
                            parameters { path("id") { description = "UUID of the lesson" } }
                            responses { HttpStatusCode.OK { schema = jsonSchema<LessonResponse>() } }
                        }

                        delete {
                            val result = contentService.setVocabDisplay(call.getPathUUID(), null, call.requirePrincipal())
                            call.respond(HttpStatusCode.OK, result)
                        }.describe {
                            summary = "Clear lesson vocab display override"
                            parameters { path("id") { description = "UUID of the lesson" } }
                            responses { HttpStatusCode.OK { schema = jsonSchema<LessonResponse>() } }
                        }
                    }

                    post("/complete") {
                        val principal = call.requirePrincipal()
                        val id = call.getPathUUID()

                        val result = lifecycleService.completeLesson(id, principal)
                        call.respond(HttpStatusCode.OK, result)
                    }.describe {
                        summary = "Complete lesson"
                        description = "Completes an in-progress lesson (teacher or admin) and closes the video room. No request body; notes are saved beforehand via PATCH /content { rawNotes }."
                        parameters {
                            path("id") {
                                description = "UUID of the lesson"
                            }
                        }
                        responses {
                            HttpStatusCode.OK {
                                description = "Lesson completed"
                                schema = jsonSchema<LessonResponse>()
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

                    route("/participants/{studentId}") {
                        post("/accept") {
                            val principal = call.requirePrincipal()
                            val id = call.getPathUUID()
                            val studentId = call.getPathUUID("studentId")

                            participantService.acceptJoinRequest(id, studentId, principal)
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
                                HttpStatusCode.Conflict {
                                    description = "CLUB_FULL: every spot is already confirmed"
                                    schema = jsonSchema<ProblemDetail>()
                                }
                                HttpStatusCode.NotFound {
                                    description = "Lesson or student not found"
                                    schema = jsonSchema<ProblemDetail>()
                                }
                            }
                        }

                        post("/reject") {
                            val principal = call.requirePrincipal()
                            val id = call.getPathUUID()
                            val studentId = call.getPathUUID("studentId")

                            participantService.rejectJoinRequest(id, studentId, principal)
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

                    post<MoveLessonRequest>("/move") {
                        val result = rescheduleService.moveLesson(call.getPathUUID(), it, call.requirePrincipal())
                        call.respond(HttpStatusCode.OK, result)
                    }.describe {
                        summary = "Move lesson"
                        description = "Teacher (own lessons) or admin moves a CONFIRMED lesson directly to a new time, optionally changing duration and topic. Working hours and blocked days do not block the move; they come back as warnings. A pending reschedule proposal is resolved by the move. Writes a LESSON_MOVED lesson event and, when notify=true, a LESSON_MOVED notification (with oldScheduledAt/newScheduledAt) to the other participants. dryRun=true runs the same checks (409 on clash) and returns the warnings without changing anything."
                        requestBody { schema = jsonSchema<MoveLessonRequest>() }
                        parameters { path("id") { description = "UUID of the lesson" } }
                        responses {
                            HttpStatusCode.OK {
                                description = "Moved (or dry run): lesson, warnings [OUTSIDE_WORKING_HOURS, BLOCKED_DAY], dryRun"
                                schema = jsonSchema<MoveLessonResponse>()
                            }
                            HttpStatusCode.BadRequest {
                                description = "LESSON_INVALID_STATE (not CONFIRMED) or invalid body (time in the past, non-positive duration, blank topic)"
                                schema = jsonSchema<ProblemDetail>()
                            }
                            HttpStatusCode.Forbidden {
                                description = "Students, or a teacher who does not own the lesson"
                                schema = jsonSchema<ProblemDetail>()
                            }
                            HttpStatusCode.NotFound {
                                description = "Lesson not found"
                                schema = jsonSchema<ProblemDetail>()
                            }
                            HttpStatusCode.Conflict {
                                description = "AVAILABILITY_OVERLAP (another lesson) or AVAILABILITY_BUFFER_CONFLICT (inside the teacher's buffer)"
                                schema = jsonSchema<ProblemDetail>()
                            }
                        }
                    }

                    route("/reschedule") {
                        post<RescheduleLessonRequest> {
                            val principal = call.requirePrincipal()
                            val id = call.getPathUUID()

                            rescheduleService.rescheduleLesson(id, it, principal)
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
                            val principal = call.requirePrincipal()
                            val id = call.getPathUUID()

                            val result = rescheduleService.acceptReschedule(id, principal)
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
                            val principal = call.requirePrincipal()
                            val id = call.getPathUUID()

                            rescheduleService.rejectReschedule(id, principal)
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

                        post("/withdraw") {
                            val principal = call.requirePrincipal()
                            val id = call.getPathUUID()

                            val result = rescheduleService.withdrawReschedule(id, principal)
                            call.respond(HttpStatusCode.OK, result)
                        }.describe {
                            summary = "Withdraw reschedule"
                            description = "Withdraws the caller's own pending reschedule request, so the lesson can be rescheduled or moved again."
                            parameters {
                                path("id") {
                                    description = "UUID of the lesson"
                                }
                            }
                            responses {
                                HttpStatusCode.OK {
                                    description = "Reschedule withdrawn; lesson without pendingReschedule"
                                    schema = jsonSchema<LessonResponse>()
                                }
                                HttpStatusCode.Forbidden {
                                    description = "Only the proposer can withdraw"
                                    schema = jsonSchema<ProblemDetail>()
                                }
                                HttpStatusCode.NotFound {
                                    description = "LESSON_NOT_FOUND or RESCHEDULE_NOT_FOUND (nothing pending)"
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