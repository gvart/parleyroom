package com.gvart.parleyroom.ai.routing

import com.gvart.parleyroom.ai.data.DraftStatus
import com.gvart.parleyroom.ai.service.DocumentDraftService
import com.gvart.parleyroom.ai.service.DraftBundleService
import com.gvart.parleyroom.ai.service.DraftSendService
import com.gvart.parleyroom.ai.transfer.DocumentDraftInput
import com.gvart.parleyroom.ai.transfer.DocumentDraftResponse
import com.gvart.parleyroom.ai.transfer.DraftBundleResponse
import com.gvart.parleyroom.ai.transfer.DraftBundleSummary
import com.gvart.parleyroom.ai.transfer.DraftContextResponse
import com.gvart.parleyroom.ai.transfer.DraftItemResponse
import com.gvart.parleyroom.ai.transfer.GenerateDraftRequest
import com.gvart.parleyroom.ai.transfer.GenerationJobResponse
import com.gvart.parleyroom.ai.transfer.PatchDraftItemRequest
import com.gvart.parleyroom.ai.transfer.RefineDraftRequest
import com.gvart.parleyroom.ai.transfer.RefineRequest
import com.gvart.parleyroom.ai.transfer.SendDraftRequest
import com.gvart.parleyroom.ai.transfer.SendDraftResponse
import com.gvart.parleyroom.common.routing.getPathUUID
import com.gvart.parleyroom.common.routing.requirePrincipal
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.document.transfer.DocumentResponse
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
import java.util.UUID

/** AI drafts (human in the loop): draft bundles, their items and Send, and draft revisions of documents. */
fun Application.configureDraftRouting() {
    val drafts: DraftBundleService by dependencies
    val sender: DraftSendService by dependencies
    val documentDrafts: DocumentDraftService by dependencies

    routing {
        authenticate {
            route("/api/v1/lessons/{id}") {
                get("/draft-context") {
                    call.respond(HttpStatusCode.OK, drafts.lessonContext(call.getPathUUID(), call.requirePrincipal()))
                }.describe {
                    summary = "AI follow-up form context for a lesson"
                    description = "Mode, AI availability, the lesson's notes, the context the server adds, past lessons with notes " +
                            "(picker) and the open draft. Lesson teacher or admin."
                    parameters { path("id") { description = "Lesson UUID" } }
                    responses { HttpStatusCode.OK { schema = jsonSchema<DraftContextResponse>() } }
                }

                post<GenerateDraftRequest>("/draft-bundles") {
                    call.respond(HttpStatusCode.Accepted, drafts.generateForLesson(call.getPathUUID(), it, call.requirePrincipal()))
                }.describe {
                    summary = "Generate the lesson's AI draft"
                    description = "Queues a GENERATE job for the lesson's open draft bundle (created if none). 1:1: the requested kinds (default words + homework: " +
                            "exercise document + 1-3 tasks); club: a notes document. Poll the bundle (or GET /api/v1/ai/jobs/{jobId}). " +
                            "Nothing reaches students before Send."
                    parameters { path("id") { description = "Lesson UUID" } }
                    requestBody { schema = jsonSchema<GenerateDraftRequest>() }
                    responses {
                        HttpStatusCode.Accepted { schema = jsonSchema<DraftBundleResponse>() }
                        HttpStatusCode.BadRequest {
                            description = "NACHBEREITUNG_NO_ATTENDEES, AI_DRAFT_NOTHING_TO_GENERATE, AI_DRAFT_PAST_LESSON_INVALID, " +
                                    "VALIDATION_FAILED (also materialIds: student drafts only)"
                            schema = jsonSchema<ProblemDetail>()
                        }
                        HttpStatusCode.Conflict { description = "AI_DRAFT_BUSY"; schema = jsonSchema<ProblemDetail>() }
                        HttpStatusCode.TooManyRequests { description = "AI_RATE_LIMITED"; schema = jsonSchema<ProblemDetail>() }
                        HttpStatusCode.ServiceUnavailable { description = "AI_NOT_CONFIGURED"; schema = jsonSchema<ProblemDetail>() }
                    }
                }
            }

            route("/api/v1/students/{studentId}") {
                get("/draft-context") {
                    call.respond(HttpStatusCode.OK, drafts.studentContext(call.getPathUUID("studentId"), call.requirePrincipal()))
                }.describe {
                    summary = "AI form context for a student"
                    description = "For out-of-lesson \"new homework\" / \"add words\": the student's context, past lessons with notes and the open draft. Teacher of the student."
                    parameters { path("studentId") { description = "Student UUID" } }
                    responses { HttpStatusCode.OK { schema = jsonSchema<DraftContextResponse>() } }
                }

                post<GenerateDraftRequest>("/draft-bundles") {
                    call.respond(HttpStatusCode.Accepted, drafts.generateForStudent(call.getPathUUID("studentId"), it, call.requirePrincipal()))
                }.describe {
                    summary = "Generate an AI draft for a student"
                    description = "Queues a GENERATE job for the student's open draft bundle with the requested kinds (required: " +
                            "[WORDS] for \"Add words\", [HOMEWORK] for \"New homework\"). Sources: past lesson notes " +
                            "(default the latest), profile, focus topics / grammar, template and prompt. With materialIds (≤ 5 of the " +
                            "teacher's own PDF / DOCX / text materials) their text is the main source: ≤ 40 words per material, words " +
                            "the student already has are left out, past notes only when picked; bundle.materials lists them (truncated flag)."
                    parameters { path("studentId") { description = "Student UUID" } }
                    requestBody { schema = jsonSchema<GenerateDraftRequest>() }
                    responses {
                        HttpStatusCode.Accepted { schema = jsonSchema<DraftBundleResponse>() }
                        HttpStatusCode.BadRequest {
                            description = "AI_DRAFT_KINDS_REQUIRED, AI_DRAFT_NOTHING_TO_GENERATE, AI_DRAFT_PAST_LESSON_INVALID, " +
                                    "AI_MATERIAL_UNSUPPORTED, AI_MATERIAL_NO_TEXT, VALIDATION_FAILED"
                            schema = jsonSchema<ProblemDetail>()
                        }
                        HttpStatusCode.NotFound { description = "MATERIAL_NOT_FOUND (unknown or another teacher's)"; schema = jsonSchema<ProblemDetail>() }
                        HttpStatusCode.Conflict { description = "AI_DRAFT_BUSY"; schema = jsonSchema<ProblemDetail>() }
                    }
                }
            }

            route("/api/v1/ai/draft-bundles") {
                get {
                    val params = call.request.queryParameters
                    val result = drafts.list(
                        call.requirePrincipal(),
                        params["status"]?.let(DraftStatus::valueOf),
                        params["lessonId"]?.let(UUID::fromString),
                        params["studentId"]?.let(UUID::fromString),
                    )
                    call.respond(HttpStatusCode.OK, result)
                }.describe {
                    summary = "List the teacher's AI drafts"
                    description = "Newest first (≤ 100). status=DRAFT gives the pending follow-ups for the dashboard."
                    responses { HttpStatusCode.OK { schema = jsonSchema<List<DraftBundleSummary>>() } }
                }

                route("/{id}") {
                    get {
                        call.respond(HttpStatusCode.OK, drafts.get(call.getPathUUID(), call.requirePrincipal()))
                    }.describe {
                        summary = "Get an AI draft"
                        parameters { path("id") { description = "Bundle UUID" } }
                        responses {
                            HttpStatusCode.OK { schema = jsonSchema<DraftBundleResponse>() }
                            HttpStatusCode.NotFound { description = "AI_DRAFT_NOT_FOUND"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    delete {
                        drafts.discard(call.getPathUUID(), call.requirePrincipal())
                        call.respond(HttpStatusCode.NoContent)
                    }.describe {
                        summary = "Discard an AI draft"
                        description = "Nothing is sent; the bundle stays as DISCARDED."
                        parameters { path("id") { description = "Bundle UUID" } }
                        responses {
                            HttpStatusCode.NoContent { description = "Discarded" }
                            HttpStatusCode.Conflict { description = "AI_DRAFT_NOT_EDITABLE (already sent)"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    patch<PatchDraftItemRequest>("/items/{itemId}") {
                        call.respond(HttpStatusCode.OK, drafts.patchItem(call.getPathUUID(), call.getPathUUID("itemId"), it, call.requirePrincipal()))
                    }.describe {
                        summary = "Edit / approve a draft item"
                        description = "Any subset of approved and the content matching the item's kind (word, document or task; replaces it)."
                        parameters {
                            path("id") { description = "Bundle UUID" }
                            path("itemId") { description = "Item UUID" }
                        }
                        requestBody { schema = jsonSchema<PatchDraftItemRequest>() }
                        responses {
                            HttpStatusCode.OK { schema = jsonSchema<DraftItemResponse>() }
                            HttpStatusCode.BadRequest {
                                description = "AI_DRAFT_ITEM_KIND_MISMATCH, AI_DRAFT_DOCUMENT_INVALID, DOCUMENT_BLOCK_INVALID, VALIDATION_FAILED"
                                schema = jsonSchema<ProblemDetail>()
                            }
                            HttpStatusCode.Conflict { description = "AI_DRAFT_NOT_EDITABLE, AI_DRAFT_BUSY"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    delete("/items/{itemId}") {
                        drafts.deleteItem(call.getPathUUID(), call.getPathUUID("itemId"), call.requirePrincipal())
                        call.respond(HttpStatusCode.NoContent)
                    }.describe {
                        summary = "Delete a draft item"
                        parameters {
                            path("id") { description = "Bundle UUID" }
                            path("itemId") { description = "Item UUID" }
                        }
                        responses { HttpStatusCode.NoContent { description = "Deleted" } }
                    }

                    post("/approve-all") {
                        call.respond(HttpStatusCode.OK, drafts.approveAll(call.getPathUUID(), call.requirePrincipal()))
                    }.describe {
                        summary = "Approve every item of a draft"
                        parameters { path("id") { description = "Bundle UUID" } }
                        responses { HttpStatusCode.OK { schema = jsonSchema<DraftBundleResponse>() } }
                    }

                    post<RefineDraftRequest>("/refine") {
                        call.respond(HttpStatusCode.Accepted, drafts.refine(call.getPathUUID(), it, call.requirePrincipal()))
                    }.describe {
                        summary = "Refine an AI draft"
                        description = "Queues a REFINE job for one item (itemId) or the whole draft's kinds (default the kinds it was generated with; " +
                            "items of other kinds stay). The refined items replace the old ones and need approving again."
                        parameters { path("id") { description = "Bundle UUID" } }
                        requestBody { schema = jsonSchema<RefineDraftRequest>() }
                        responses {
                            HttpStatusCode.Accepted { schema = jsonSchema<DraftBundleResponse>() }
                            HttpStatusCode.Conflict { description = "AI_DRAFT_NOT_EDITABLE, AI_DRAFT_BUSY, AI_DRAFT_EMPTY"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    post<SendDraftRequest>("/send") {
                        call.respond(HttpStatusCode.OK, sender.send(call.getPathUUID(), it, call.requirePrincipal()))
                    }.describe {
                        summary = "Send the approved items"
                        description = "One transaction: approved words to the library and the recipients, the exercise document + tasks " +
                            "as one assignment (HOMEWORK_ASSIGNED), documents shared. Idempotent: a repeated Send returns the stored result. " +
                            "Never writes the lesson's notes."
                        parameters { path("id") { description = "Bundle UUID" } }
                        requestBody { schema = jsonSchema<SendDraftRequest>() }
                        responses {
                            HttpStatusCode.OK { schema = jsonSchema<SendDraftResponse>() }
                            HttpStatusCode.BadRequest {
                                description = "AI_DRAFT_NOTHING_APPROVED, AI_DRAFT_RECIPIENT_INVALID, AI_DRAFT_NO_RECIPIENTS, STUDENT_NOT_LINKED"
                                schema = jsonSchema<ProblemDetail>()
                            }
                            HttpStatusCode.Conflict { description = "AI_DRAFT_NOT_EDITABLE (discarded), AI_DRAFT_BUSY"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }
                }
            }

            route("/api/v1/documents/{id}") {
                post<RefineRequest>("/ai-refine") {
                    call.respond(HttpStatusCode.Accepted, documentDrafts.refine(call.getPathUUID(), it.instruction, call.requirePrincipal()))
                }.describe {
                    summary = "AI refine of a document (as a draft revision)"
                    description = "Queues a REFINE job; on success the result is stored as the document's draft revision. " +
                            "Students keep reading the published content until POST …/draft/publish."
                    parameters { path("id") { description = "Document UUID" } }
                    requestBody { schema = jsonSchema<RefineRequest>() }
                    responses {
                        HttpStatusCode.Accepted { schema = jsonSchema<GenerationJobResponse>() }
                        HttpStatusCode.ServiceUnavailable { description = "AI_NOT_CONFIGURED"; schema = jsonSchema<ProblemDetail>() }
                    }
                }

                route("/draft") {
                    get {
                        val id = call.getPathUUID()
                        val principal = call.requirePrincipal()
                        if (call.request.queryParameters["optional"] == "true") {
                            val draft = documentDrafts.find(id, principal)
                            if (draft == null) call.respond(HttpStatusCode.NoContent) else call.respond(HttpStatusCode.OK, draft)
                        } else {
                            call.respond(HttpStatusCode.OK, documentDrafts.get(id, principal))
                        }
                    }.describe {
                        summary = "Get the document's draft revision"
                        description = "With optional=true, no draft is 204 instead of 404 (for pages that only check whether one exists)."
                        parameters {
                            path("id") { description = "Document UUID" }
                            query("optional") { description = "true: 204 when there is no draft"; required = false }
                        }
                        responses {
                            HttpStatusCode.OK { schema = jsonSchema<DocumentDraftResponse>() }
                            HttpStatusCode.NoContent { description = "No draft (optional=true)" }
                            HttpStatusCode.NotFound { description = "DOCUMENT_DRAFT_NOT_FOUND"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    put<DocumentDraftInput> {
                        call.respond(HttpStatusCode.OK, documentDrafts.update(call.getPathUUID(), it, call.requirePrincipal()))
                    }.describe {
                        summary = "Edit the document's draft revision"
                        parameters { path("id") { description = "Document UUID" } }
                        requestBody { schema = jsonSchema<DocumentDraftInput>() }
                        responses { HttpStatusCode.OK { schema = jsonSchema<DocumentDraftResponse>() } }
                    }

                    post("/publish") {
                        call.respond(HttpStatusCode.OK, documentDrafts.publish(call.getPathUUID(), call.requirePrincipal()))
                    }.describe {
                        summary = "Publish the draft revision"
                        description = "Snapshot of the current content (AI_REFINE), then the draft replaces title + blocks (revision +1)."
                        parameters { path("id") { description = "Document UUID" } }
                        responses { HttpStatusCode.OK { schema = jsonSchema<DocumentResponse>() } }
                    }

                    delete {
                        documentDrafts.discard(call.getPathUUID(), call.requirePrincipal())
                        call.respond(HttpStatusCode.NoContent)
                    }.describe {
                        summary = "Discard the draft revision"
                        parameters { path("id") { description = "Document UUID" } }
                        responses { HttpStatusCode.NoContent { description = "Discarded" } }
                    }
                }
            }
        }
    }
}
