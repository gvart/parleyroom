package com.gvart.parleyroom.lessonprep.routing

import com.gvart.parleyroom.common.routing.idempotencyKeyHeader
import com.gvart.parleyroom.common.routing.respondIdempotent
import com.gvart.parleyroom.common.service.IdempotencyService
import com.gvart.parleyroom.admin.data.FeatureFlag
import com.gvart.parleyroom.admin.service.FeatureFlagService
import com.gvart.parleyroom.common.routing.getPathUUID
import com.gvart.parleyroom.common.routing.requirePrincipal
import com.gvart.parleyroom.common.transfer.PageRequest
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.lessonprep.service.LessonHistoryService
import com.gvart.parleyroom.lessonprep.service.LessonPrepService
import com.gvart.parleyroom.lessonprep.transfer.CreateLessonPlanRequest
import com.gvart.parleyroom.lessonprep.transfer.LessonHistoryResponse
import com.gvart.parleyroom.lessonprep.transfer.LessonPlanPatch
import com.gvart.parleyroom.lessonprep.transfer.LessonPlanResponse
import com.gvart.parleyroom.lessonprep.transfer.NextLessonPrepResponse
import com.gvart.parleyroom.lessonprep.transfer.RegenerateLessonPlanRequest
import com.gvart.parleyroom.lessonprep.transfer.WrapUpRequest
import com.gvart.parleyroom.lessonprep.transfer.WrapUpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.Application
import io.ktor.server.application.createRouteScopedPlugin
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

private const val PLAN = "The plan's teacher or an admin; students get 403, other teachers 404 LESSON_PLAN_NOT_FOUND."
private const val STUDENT = "A teacher linked to the student or an admin; students get 403, other teachers 404 STUDENT_NOT_FOUND."
private const val JOB = "Starts a LESSON_PREP job (poll GET /api/v1/ai/jobs/{id} or the plan's job); the plan is written when it succeeds."

fun Application.configureLessonPrepRouting() {
    val prep: LessonPrepService by dependencies
    val history: LessonHistoryService by dependencies
    val flags: FeatureFlagService by dependencies
    val idempotency: IdempotencyService by dependencies

    // Feature flag `lessonPrep` (D-007): while it is off these routes do not exist.
    val lessonPrepFlag = createRouteScopedPlugin("LessonPrepFeatureFlag") {
        onCall { if (!flags.isEnabled(FeatureFlag.LESSON_PREP)) throw NotFoundException("Not found") }
    }

    routing {
        authenticate {
            route("/api/v1/lesson-plans") {
                install(lessonPrepFlag)

                post<CreateLessonPlanRequest> {
                    call.respondIdempotent(idempotency, it, HttpStatusCode.Accepted) { prep.create(it, call.requirePrincipal()) }
                }.describe {
                    idempotencyKeyHeader()
                    summary = "Prepare a lesson with AI"
                    description = "$STUDENT $JOB Links lessonId (a lesson of the teacher with the student) or, without it, the " +
                            "student's next upcoming CONFIRMED lesson that has no plan yet (none: unlinked). The student's ACTIVE goal " +
                            "is attached. prepNote 1..4000; explainInRussian allows short Russian notes for the teacher."
                    requestBody { schema = jsonSchema<CreateLessonPlanRequest>() }
                    responses {
                        HttpStatusCode.Accepted { description = "The plan (plan null until the job succeeds) with its job"; schema = jsonSchema<LessonPlanResponse>() }
                        HttpStatusCode.BadRequest { description = "VALIDATION_FAILED, LESSON_PLAN_LESSON_INVALID"; schema = jsonSchema<ProblemDetail>() }
                        HttpStatusCode.Conflict { description = "LESSON_PLAN_EXISTS (the lesson has a plan; existingId is its id)"; schema = jsonSchema<ProblemDetail>() }
                        HttpStatusCode.TooManyRequests { description = "AI_RATE_LIMITED"; schema = jsonSchema<ProblemDetail>() }
                        HttpStatusCode.ServiceUnavailable { description = "AI_NOT_CONFIGURED"; schema = jsonSchema<ProblemDetail>() }
                    }
                }

                route("/{id}") {
                    get {
                        call.respond(prep.get(call.getPathUUID(), call.requirePrincipal()))
                    }.describe {
                        summary = "Get a lesson plan"
                        description = "$PLAN With the linked lesson, the targeted roadmap items and the latest job."
                        parameters { path("id") { description = "Lesson plan UUID" } }
                        responses {
                            HttpStatusCode.OK { schema = jsonSchema<LessonPlanResponse>() }
                            HttpStatusCode.NotFound { description = "LESSON_PLAN_NOT_FOUND"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    patch<LessonPlanPatch> {
                        call.respond(prep.patch(call.getPathUUID(), it, call.requirePrincipal()))
                    }.describe {
                        summary = "Edit a lesson plan"
                        description = "$PLAN Absent fields stay. plan replaces the whole plan (shape checked; roadmapItemIds must be " +
                                "items of the plan's goal). \"\" clears coveredNote and unlinks lessonId. status DRAFT | READY. " +
                                "Field errors are 400 VALIDATION_FAILED with errors[].pointer (e.g. /plan/timePlan/0/minutes)."
                        parameters { path("id") { description = "Lesson plan UUID" } }
                        requestBody { schema = jsonSchema<LessonPlanPatch>() }
                        responses {
                            HttpStatusCode.OK { schema = jsonSchema<LessonPlanResponse>() }
                            HttpStatusCode.BadRequest {
                                description = "VALIDATION_FAILED, LESSON_PLAN_LESSON_INVALID, LESSON_PLAN_ITEM_INVALID"; schema = jsonSchema<ProblemDetail>()
                            }
                            HttpStatusCode.Conflict { description = "LESSON_PLAN_EXISTS"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    delete {
                        prep.delete(call.getPathUUID(), call.requirePrincipal())
                        call.respond(HttpStatusCode.NoContent)
                    }.describe {
                        summary = "Delete a lesson plan"
                        description = PLAN
                        parameters { path("id") { description = "Lesson plan UUID" } }
                        responses { HttpStatusCode.NoContent { description = "Deleted" } }
                    }

                    post<RegenerateLessonPlanRequest>("/regenerate") {
                        call.respond(HttpStatusCode.Accepted, prep.regenerate(call.getPathUUID(), it, call.requirePrincipal()))
                    }.describe {
                        summary = "Prepare the lesson again with AI"
                        description = "$PLAN $JOB Keeps the lesson link; an optional new prepNote / explainInRussian replaces the stored one. " +
                                "The new plan replaces the old one (status DRAFT)."
                        parameters { path("id") { description = "Lesson plan UUID" } }
                        requestBody { schema = jsonSchema<RegenerateLessonPlanRequest>() }
                        responses {
                            HttpStatusCode.Accepted { schema = jsonSchema<LessonPlanResponse>() }
                            HttpStatusCode.Conflict { description = "LESSON_PLAN_BUSY, LESSON_PLAN_DONE"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }
                }
            }

            route("/api/v1/lessons/{lessonId}/plan") {
                install(lessonPrepFlag)

                get {
                    call.respond(prep.getByLesson(call.getPathUUID("lessonId"), call.requirePrincipal()))
                }.describe {
                    summary = "Get a lesson's plan"
                    description = "$PLAN The plan linked to the lesson (at most one), as GET /lesson-plans/{id}."
                    parameters { path("lessonId") { description = "Lesson UUID" } }
                    responses {
                        HttpStatusCode.OK { schema = jsonSchema<LessonPlanResponse>() }
                        HttpStatusCode.NotFound {
                            description = "LESSON_PLAN_NOT_FOUND (no plan, or another teacher's)"; schema = jsonSchema<ProblemDetail>()
                        }
                    }
                }
            }

            route("/api/v1/lessons/{lessonId}/wrap-up") {
                install(lessonPrepFlag)

                post<WrapUpRequest> {
                    call.respond(prep.wrapUp(call.getPathUUID("lessonId"), it, call.requirePrincipal()))
                }.describe {
                    summary = "Wrap up a lesson"
                    description = "The lesson's teacher or an admin (students 403, others 404 LESSON_NOT_FOUND). Saves coveredNote, sets the " +
                            "lesson's plan DONE and sets the roadmap item states with this lesson (as PATCH /goals/{id}/items, all or " +
                            "nothing). A 1:1 lesson without a plan gets one. Idempotent. Returns the plan's suggestedHomework: hand its " +
                            "prompt to POST /api/v1/lessons/{id}/draft-bundles { prompt, kinds: [HOMEWORK] }."
                    parameters { path("lessonId") { description = "Lesson UUID" } }
                    requestBody { schema = jsonSchema<WrapUpRequest>() }
                    responses {
                        HttpStatusCode.OK { schema = jsonSchema<WrapUpResponse>() }
                        HttpStatusCode.BadRequest {
                            description = "VALIDATION_FAILED, LESSON_PLAN_NO_GOAL, ROADMAP_LESSON_INVALID"; schema = jsonSchema<ProblemDetail>()
                        }
                        HttpStatusCode.NotFound {
                            description = "LESSON_NOT_FOUND, ROADMAP_ITEM_NOT_FOUND, LESSON_PLAN_NOT_FOUND (group lesson without a plan)"
                            schema = jsonSchema<ProblemDetail>()
                        }
                    }
                }
            }

            route("/api/v1/students/{studentId}/lesson-history") {
                install(lessonPrepFlag)

                get {
                    call.respond(history.history(call.getPathUUID("studentId"), call.requirePrincipal(), PageRequest.from(call)))
                }.describe {
                    summary = "A student's lesson history"
                    description = "$STUDENT Lessons of the teacher with the student that took place (admins: all): scheduled in the past, " +
                            "or taught early (IN_PROGRESS, COMPLETED or wrapped up), newest first, paginated, " +
                            "with raw notes, the plan (lessonGoal, status), covered note, roadmap items checked in the lesson and the " +
                            "student's homework from it. nextLesson: the upcoming CONFIRMED lesson not wrapped up yet, on every page."
                    parameters {
                        path("studentId") { description = "Student UUID" }
                        query("page") { description = "1-based page (default 1)"; required = false }
                        query("pageSize") { description = "1..100 (default 20)"; required = false }
                    }
                    responses { HttpStatusCode.OK { schema = jsonSchema<LessonHistoryResponse>() } }
                }
            }

            route("/api/v1/students/{studentId}/next-lesson-prep") {
                install(lessonPrepFlag)

                get {
                    call.respond(prep.nextLessonPrep(call.getPathUUID("studentId"), call.requirePrincipal()))
                }.describe {
                    summary = "The student's next lesson and its plan"
                    description = "$STUDENT For the \"Prepare next lesson\" button: the upcoming CONFIRMED lesson (null without one) " +
                            "and its plan (null when not prepared yet)."
                    parameters { path("studentId") { description = "Student UUID" } }
                    responses { HttpStatusCode.OK { schema = jsonSchema<NextLessonPrepResponse>() } }
                }
            }
        }
    }
}
