package com.gvart.parleyroom.goal.routing

import com.gvart.parleyroom.admin.data.FeatureFlag
import com.gvart.parleyroom.admin.service.FeatureFlagService
import com.gvart.parleyroom.ai.transfer.GenerationJobResponse
import com.gvart.parleyroom.common.routing.getPathUUID
import com.gvart.parleyroom.common.routing.getQueryUUID
import com.gvart.parleyroom.common.routing.requirePrincipal
import com.gvart.parleyroom.common.transfer.PageRequest
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.goal.data.GoalStatus
import com.gvart.parleyroom.goal.service.GoalService
import com.gvart.parleyroom.goal.service.RoadmapGenerationService
import com.gvart.parleyroom.goal.service.RoadmapService
import com.gvart.parleyroom.goal.transfer.BatchItemStatesRequest
import com.gvart.parleyroom.goal.transfer.GenerateRoadmapRequest
import com.gvart.parleyroom.goal.transfer.GoalInput
import com.gvart.parleyroom.goal.transfer.GoalPageResponse
import com.gvart.parleyroom.goal.transfer.GoalPatch
import com.gvart.parleyroom.goal.transfer.ItemInput
import com.gvart.parleyroom.goal.transfer.ItemPatch
import com.gvart.parleyroom.goal.transfer.MilestoneInput
import com.gvart.parleyroom.goal.transfer.MilestonePatch
import com.gvart.parleyroom.goal.transfer.MoveItemRequest
import com.gvart.parleyroom.goal.transfer.MyGoalResponse
import com.gvart.parleyroom.goal.transfer.RegenerateMilestoneRequest
import com.gvart.parleyroom.goal.transfer.ReorderRequest
import com.gvart.parleyroom.goal.transfer.SelfAssessmentInput
import com.gvart.parleyroom.goal.transfer.TeacherGoalView
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
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

private const val VIEW = "The goal's full teacher view (TeacherGoalView)."
private const val TEACHER = "The goal's teacher or an admin; students get 403, other teachers 404 GOAL_NOT_FOUND."

fun Application.configureGoalRouting() {
    val goals: GoalService by dependencies
    val roadmap: RoadmapService by dependencies
    val generation: RoadmapGenerationService by dependencies
    val flags: FeatureFlagService by dependencies

    // Feature flag `roadmap` (D-007): while it is off the goal routes do not exist.
    val roadmapFlag = createRouteScopedPlugin("RoadmapFeatureFlag") {
        onCall { if (!flags.isEnabled(FeatureFlag.ROADMAP)) throw NotFoundException("Not found") }
    }

    routing {
        authenticate {
            route("/api/v1/goals") {
                install(roadmapFlag)

                get {
                    val statuses = call.request.queryParameters["status"]?.split(',')?.map { raw ->
                        GoalStatus.entries.firstOrNull { it.name == raw.trim() }
                            ?: throw BadRequestException("Unknown goal status: $raw", "GOAL_INVALID", "/status")
                    }
                    call.respond(goals.list(call.requirePrincipal(), call.getQueryUUID("studentId"), statuses, PageRequest.from(call)))
                }.describe {
                    summary = "List goals"
                    description = "Teachers: the goals they set; admins: all; students 403 (use /goals/my). " +
                            "ACTIVE first, then deadline asc, createdAt desc. Summaries with progress, no roadmap."
                    parameters {
                        query("studentId") { description = "Filter by student UUID"; required = false }
                        query("status") { description = "Comma list of ACTIVE, ACHIEVED, ARCHIVED"; required = false }
                        query("page") { description = "1-based page (default 1)"; required = false }
                        query("pageSize") { description = "1..100 (default 20)"; required = false }
                    }
                    responses { HttpStatusCode.OK { schema = jsonSchema<GoalPageResponse>() } }
                }

                post<GoalInput> {
                    call.respond(HttpStatusCode.Created, goals.create(it, call.requirePrincipal()))
                }.describe {
                    summary = "Create goal"
                    description = "Teacher only, for a linked student (403). At most one ACTIVE goal per student (409 GOAL_ACTIVE_EXISTS). " +
                            "Field errors are 400 VALIDATION_FAILED with errors[].pointer."
                    requestBody { schema = jsonSchema<GoalInput>() }
                    responses {
                        HttpStatusCode.Created { description = VIEW; schema = jsonSchema<TeacherGoalView>() }
                        HttpStatusCode.BadRequest { description = "VALIDATION_FAILED"; schema = jsonSchema<ProblemDetail>() }
                        HttpStatusCode.Conflict { description = "GOAL_ACTIVE_EXISTS"; schema = jsonSchema<ProblemDetail>() }
                    }
                }

                route("/my") {
                    get {
                        call.respond(goals.my(call.requirePrincipal()))
                    }.describe {
                        summary = "My goal (student)"
                        description = "The student's ACTIVE goal, minimal: title, deadline, progress, status, milestone path " +
                                "(position, done, current), current/next can-do, covered milestone titles and a pending self-assessment. " +
                                "goal is null without an active goal. Students only (403)."
                        responses { HttpStatusCode.OK { schema = jsonSchema<MyGoalResponse>() } }
                    }

                    post<SelfAssessmentInput>("/milestones/{milestoneId}/self-assessment") {
                        call.respond(roadmap.selfAssess(call.getPathUUID("milestoneId"), it, call.requirePrincipal()))
                    }.describe {
                        summary = "Self-assess a finished milestone (student)"
                        description = "answer YES | PARTLY | NOT_YET, comment ≤ 1000. Answering again replaces the answer. " +
                                "Anything but YES notifies the teacher (SELF_ASSESSMENT_MISMATCH)."
                        parameters { path("milestoneId") { description = "Milestone UUID" } }
                        requestBody { schema = jsonSchema<SelfAssessmentInput>() }
                        responses {
                            HttpStatusCode.OK { schema = jsonSchema<MyGoalResponse>() }
                            HttpStatusCode.NotFound { description = "ROADMAP_MILESTONE_NOT_FOUND (also another student's)"; schema = jsonSchema<ProblemDetail>() }
                            HttpStatusCode.Conflict { description = "MILESTONE_NOT_DONE"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }
                }

                route("/{id}") {
                    get {
                        call.respond(goals.get(call.getPathUUID(), call.requirePrincipal()))
                    }.describe {
                        summary = "Get goal (teacher view)"
                        description = "$TEACHER Goal, milestones with items, progress, per-milestone progress, self-assessments " +
                                "(mismatch = done but not YES), openMismatches and the running roadmap job."
                        parameters { path("id") { description = "Goal UUID" } }
                        responses {
                            HttpStatusCode.OK { schema = jsonSchema<TeacherGoalView>() }
                            HttpStatusCode.NotFound { description = "GOAL_NOT_FOUND"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    patch<GoalPatch> {
                        call.respond(goals.patch(call.getPathUUID(), it, call.requirePrincipal()))
                    }.describe {
                        summary = "Update goal"
                        description = "$TEACHER Absent fields stay; \"\" clears exam, targetLevel, levelNote, interests, materials. " +
                                "status ARCHIVED archives. Reactivating while another goal is ACTIVE is 409 GOAL_ACTIVE_EXISTS."
                        parameters { path("id") { description = "Goal UUID" } }
                        requestBody { schema = jsonSchema<GoalPatch>() }
                        responses {
                            HttpStatusCode.OK { schema = jsonSchema<TeacherGoalView>() }
                            HttpStatusCode.BadRequest { description = "VALIDATION_FAILED, GOAL_INVALID"; schema = jsonSchema<ProblemDetail>() }
                            HttpStatusCode.Conflict { description = "GOAL_ACTIVE_EXISTS"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    delete {
                        goals.delete(call.getPathUUID(), call.requirePrincipal())
                        call.respond(HttpStatusCode.NoContent)
                    }.describe {
                        summary = "Delete goal"
                        description = "$TEACHER Deletes the goal with its roadmap."
                        parameters { path("id") { description = "Goal UUID" } }
                        responses { HttpStatusCode.NoContent { description = "Deleted" } }
                    }

                    post<GenerateRoadmapRequest>("/roadmap/generate") {
                        call.respond(HttpStatusCode.Accepted, generation.generate(call.getPathUUID(), it, call.requirePrincipal()))
                    }.describe {
                        summary = "Generate the roadmap with AI"
                        description = "$TEACHER Starts a ROADMAP job (poll GET /api/v1/ai/jobs/{id}); the roadmap is written when it " +
                                "succeeds. Only for a goal without milestones unless replace=true. Optional instruction ≤ 1000."
                        parameters { path("id") { description = "Goal UUID" } }
                        requestBody { schema = jsonSchema<GenerateRoadmapRequest>() }
                        responses {
                            HttpStatusCode.Accepted { schema = jsonSchema<GenerationJobResponse>() }
                            HttpStatusCode.Conflict { description = "ROADMAP_EXISTS, ROADMAP_BUSY"; schema = jsonSchema<ProblemDetail>() }
                            HttpStatusCode.TooManyRequests { description = "AI_RATE_LIMITED"; schema = jsonSchema<ProblemDetail>() }
                            HttpStatusCode.ServiceUnavailable { description = "AI_NOT_CONFIGURED"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    route("/milestones") {
                        post<MilestoneInput> {
                            call.respond(HttpStatusCode.Created, roadmap.createMilestone(call.getPathUUID(), it, call.requirePrincipal()))
                        }.describe {
                            summary = "Add milestone"
                            description = "$TEACHER At position (1-based, default: last); later milestones move down. Answers $VIEW"
                            parameters { path("id") { description = "Goal UUID" } }
                            requestBody { schema = jsonSchema<MilestoneInput>() }
                            responses { HttpStatusCode.Created { schema = jsonSchema<TeacherGoalView>() } }
                        }

                        put<ReorderRequest>("/order") {
                            call.respond(roadmap.reorderMilestones(call.getPathUUID(), it, call.requirePrincipal()))
                        }.describe {
                            summary = "Reorder milestones"
                            description = "$TEACHER ids: every milestone of the goal exactly once, in the new order (else 400 ROADMAP_ORDER_INVALID)."
                            parameters { path("id") { description = "Goal UUID" } }
                            requestBody { schema = jsonSchema<ReorderRequest>() }
                            responses { HttpStatusCode.OK { schema = jsonSchema<TeacherGoalView>() } }
                        }

                        route("/{milestoneId}") {
                            patch<MilestonePatch> {
                                call.respond(roadmap.patchMilestone(call.getPathUUID(), call.getPathUUID("milestoneId"), it, call.requirePrincipal()))
                            }.describe {
                                summary = "Update milestone"
                                description = "$TEACHER Rename, dates, weight, content. \"\" clears topic, examPart, teacherTips."
                                parameters { path("id") { description = "Goal UUID" }; path("milestoneId") { description = "Milestone UUID" } }
                                requestBody { schema = jsonSchema<MilestonePatch>() }
                                responses {
                                    HttpStatusCode.OK { schema = jsonSchema<TeacherGoalView>() }
                                    HttpStatusCode.NotFound { description = "ROADMAP_MILESTONE_NOT_FOUND"; schema = jsonSchema<ProblemDetail>() }
                                }
                            }

                            delete {
                                call.respond(roadmap.deleteMilestone(call.getPathUUID(), call.getPathUUID("milestoneId"), call.requirePrincipal()))
                            }.describe {
                                summary = "Delete milestone"
                                description = "$TEACHER With its items and self-assessment."
                                parameters { path("id") { description = "Goal UUID" }; path("milestoneId") { description = "Milestone UUID" } }
                                responses { HttpStatusCode.OK { schema = jsonSchema<TeacherGoalView>() } }
                            }

                            post<RegenerateMilestoneRequest>("/regenerate") {
                                val result = generation.regenerate(call.getPathUUID(), call.getPathUUID("milestoneId"), it, call.requirePrincipal())
                                call.respond(HttpStatusCode.Accepted, result)
                            }.describe {
                                summary = "Regenerate one milestone with AI"
                                description = "$TEACHER Starts a MILESTONE_REGEN job: the milestone keeps its position, dates and weight; " +
                                        "content and items are replaced when it succeeds. Not for a done milestone (409 ROADMAP_MILESTONE_DONE)."
                                parameters { path("id") { description = "Goal UUID" }; path("milestoneId") { description = "Milestone UUID" } }
                                requestBody { schema = jsonSchema<RegenerateMilestoneRequest>() }
                                responses {
                                    HttpStatusCode.Accepted { schema = jsonSchema<GenerationJobResponse>() }
                                    HttpStatusCode.Conflict { description = "ROADMAP_MILESTONE_DONE, ROADMAP_BUSY"; schema = jsonSchema<ProblemDetail>() }
                                }
                            }

                            post("/done") {
                                call.respond(roadmap.markDone(call.getPathUUID(), call.getPathUUID("milestoneId"), call.requirePrincipal()))
                            }.describe {
                                summary = "Mark milestone done"
                                description = "$TEACHER Notifies the student (MILESTONE_COMPLETED) to self-assess. Repeating it is a no-op."
                                parameters { path("id") { description = "Goal UUID" }; path("milestoneId") { description = "Milestone UUID" } }
                                responses { HttpStatusCode.OK { schema = jsonSchema<TeacherGoalView>() } }
                            }

                            delete("/done") {
                                call.respond(roadmap.markUndone(call.getPathUUID(), call.getPathUUID("milestoneId"), call.requirePrincipal()))
                            }.describe {
                                summary = "Mark milestone not done"
                                description = TEACHER
                                parameters { path("id") { description = "Goal UUID" }; path("milestoneId") { description = "Milestone UUID" } }
                                responses { HttpStatusCode.OK { schema = jsonSchema<TeacherGoalView>() } }
                            }

                            post<ItemInput>("/items") {
                                val result = roadmap.createItem(call.getPathUUID(), call.getPathUUID("milestoneId"), it, call.requirePrincipal())
                                call.respond(HttpStatusCode.Created, result)
                            }.describe {
                                summary = "Add item"
                                description = "$TEACHER kind TOPIC | GRAMMAR | VOCAB | SKILL | EXAM, title ≤ 300, position (default: last)."
                                parameters { path("id") { description = "Goal UUID" }; path("milestoneId") { description = "Milestone UUID" } }
                                requestBody { schema = jsonSchema<ItemInput>() }
                                responses { HttpStatusCode.Created { schema = jsonSchema<TeacherGoalView>() } }
                            }

                            put<ReorderRequest>("/items/order") {
                                val result = roadmap.reorderItems(call.getPathUUID(), call.getPathUUID("milestoneId"), it, call.requirePrincipal())
                                call.respond(result)
                            }.describe {
                                summary = "Reorder items"
                                description = "$TEACHER ids: every item of the milestone exactly once (else 400 ROADMAP_ORDER_INVALID)."
                                parameters { path("id") { description = "Goal UUID" }; path("milestoneId") { description = "Milestone UUID" } }
                                requestBody { schema = jsonSchema<ReorderRequest>() }
                                responses { HttpStatusCode.OK { schema = jsonSchema<TeacherGoalView>() } }
                            }
                        }
                    }

                    route("/items") {
                        patch<BatchItemStatesRequest> {
                            call.respond(roadmap.batchStates(call.getPathUUID(), it, call.requirePrincipal()))
                        }.describe {
                            summary = "Set many item states (after a lesson)"
                            description = "$TEACHER items: [{itemId, state}] (≤ 200), optional lessonId (a lesson of this teacher with the " +
                                    "student, else 400 ROADMAP_LESSON_INVALID). All or nothing; idempotent (same body twice = same result)."
                            parameters { path("id") { description = "Goal UUID" } }
                            requestBody { schema = jsonSchema<BatchItemStatesRequest>() }
                            responses {
                                HttpStatusCode.OK { schema = jsonSchema<TeacherGoalView>() }
                                HttpStatusCode.NotFound { description = "ROADMAP_ITEM_NOT_FOUND"; schema = jsonSchema<ProblemDetail>() }
                            }
                        }

                        route("/{itemId}") {
                            patch<ItemPatch> {
                                call.respond(roadmap.patchItem(call.getPathUUID(), call.getPathUUID("itemId"), it, call.requirePrincipal()))
                            }.describe {
                                summary = "Update item"
                                description = "$TEACHER Rename, kind, state NOT_STARTED | IN_PROGRESS | DONE with optional lessonId."
                                parameters { path("id") { description = "Goal UUID" }; path("itemId") { description = "Item UUID" } }
                                requestBody { schema = jsonSchema<ItemPatch>() }
                                responses {
                                    HttpStatusCode.OK { schema = jsonSchema<TeacherGoalView>() }
                                    HttpStatusCode.NotFound { description = "ROADMAP_ITEM_NOT_FOUND"; schema = jsonSchema<ProblemDetail>() }
                                }
                            }

                            delete {
                                call.respond(roadmap.deleteItem(call.getPathUUID(), call.getPathUUID("itemId"), call.requirePrincipal()))
                            }.describe {
                                summary = "Delete item"
                                description = TEACHER
                                parameters { path("id") { description = "Goal UUID" }; path("itemId") { description = "Item UUID" } }
                                responses { HttpStatusCode.OK { schema = jsonSchema<TeacherGoalView>() } }
                            }

                            post<MoveItemRequest>("/move") {
                                call.respond(roadmap.moveItem(call.getPathUUID(), call.getPathUUID("itemId"), it, call.requirePrincipal()))
                            }.describe {
                                summary = "Move item to another milestone"
                                description = "$TEACHER milestoneId of the same goal, position (default: last). Keeps state and lesson."
                                parameters { path("id") { description = "Goal UUID" }; path("itemId") { description = "Item UUID" } }
                                requestBody { schema = jsonSchema<MoveItemRequest>() }
                                responses { HttpStatusCode.OK { schema = jsonSchema<TeacherGoalView>() } }
                            }
                        }
                    }
                }
            }
        }
    }
}
