package com.gvart.parleyroom.ai.routing

import com.gvart.parleyroom.topic.service.LibraryAccess
import com.gvart.parleyroom.ai.transfer.AiStatusResponse
import com.gvart.parleyroom.ai.config.AiRuntime
import com.gvart.parleyroom.ai.data.PromptTemplateLessonType
import com.gvart.parleyroom.ai.service.FillTranslationsService
import com.gvart.parleyroom.ai.service.GenerationJobs
import com.gvart.parleyroom.ai.service.LibrarySuggestionService
import com.gvart.parleyroom.ai.service.PromptTemplateService
import com.gvart.parleyroom.ai.transfer.ApplyFillProposalsRequest
import com.gvart.parleyroom.ai.transfer.FillMissingRequest
import com.gvart.parleyroom.ai.transfer.GenerationJobResponse
import com.gvart.parleyroom.ai.transfer.LibrarySuggestions
import com.gvart.parleyroom.ai.transfer.MissingFieldsResponse
import com.gvart.parleyroom.ai.transfer.PromptTemplateInput
import com.gvart.parleyroom.ai.transfer.PromptTemplateResponse
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.routing.getPathUUID
import com.gvart.parleyroom.common.routing.requirePrincipal
import com.gvart.parleyroom.common.transfer.ProblemDetail
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureAiRouting() {
    val templates: PromptTemplateService by dependencies
    val suggestions: LibrarySuggestionService by dependencies
    val fillService: FillTranslationsService by dependencies
    val aiRuntime: AiRuntime by dependencies

    routing {
        authenticate {
            route("/api/v1/lessons/{id}") {
                get("/library-suggestions") {
                    call.respond(HttpStatusCode.OK, suggestions.suggestions(call.getPathUUID(), call.requirePrincipal()))
                }.describe {
                    summary = "Library suggestions for a lesson"
                    description = "Existing documents and materials of the lesson's level sharing its topics/grammar. No AI."
                    parameters { path("id") { description = "Lesson UUID" } }
                    responses { HttpStatusCode.OK { schema = jsonSchema<LibrarySuggestions>() } }
                }
            }

            route("/api/v1/ai/jobs/{id}") {
                get {
                    call.respond(HttpStatusCode.OK, GenerationJobs.get(call.getPathUUID(), call.requirePrincipal()))
                }.describe {
                    summary = "Get AI job"
                    description = "Poll until status is SUCCEEDED or FAILED."
                    parameters { path("id") { description = "Job UUID" } }
                    responses {
                        HttpStatusCode.OK { schema = jsonSchema<GenerationJobResponse>() }
                        HttpStatusCode.NotFound { description = "AI_JOB_NOT_FOUND"; schema = jsonSchema<ProblemDetail>() }
                    }
                }

                post<ApplyFillProposalsRequest>("/fill-proposals/apply") {
                    call.respond(HttpStatusCode.OK, fillService.apply(call.getPathUUID(), it, call.requirePrincipal()))
                }.describe {
                    summary = "Apply fill-missing proposals"
                    description = "Writes the selected proposals of a SUCCEEDED FILL_TRANSLATIONS job to vocab_entries " +
                            "(optionally with the teacher's own value). Fields filled meanwhile are not overwritten (reported as stale). " +
                            "Unselected proposals are dropped; the job's proposals are then resolved."
                    parameters { path("id") { description = "Job UUID" } }
                    requestBody { schema = jsonSchema<ApplyFillProposalsRequest>() }
                    responses {
                        HttpStatusCode.OK { schema = jsonSchema<GenerationJobResponse>() }
                        HttpStatusCode.Conflict { description = "AI_JOB_NOT_READY, AI_PROPOSALS_RESOLVED"; schema = jsonSchema<ProblemDetail>() }
                    }
                }

                post("/fill-proposals/reject") {
                    call.respond(HttpStatusCode.OK, fillService.reject(call.getPathUUID(), call.requirePrincipal()))
                }.describe {
                    summary = "Reject fill-missing proposals"
                    description = "Discards all proposals of the job; vocab_entries stay unchanged."
                    parameters { path("id") { description = "Job UUID" } }
                    responses {
                        HttpStatusCode.OK { schema = jsonSchema<GenerationJobResponse>() }
                        HttpStatusCode.Conflict { description = "AI_JOB_NOT_READY, AI_PROPOSALS_RESOLVED"; schema = jsonSchema<ProblemDetail>() }
                    }
                }
            }

            route("/api/v1/prompt-templates") {
                get {
                    val params = call.request.queryParameters
                    val result = templates.list(
                        call.requirePrincipal(),
                        params["lessonType"]?.let(PromptTemplateLessonType::valueOf),
                        params["level"]?.let(LanguageLevel::valueOf),
                    )
                    call.respond(HttpStatusCode.OK, result)
                }.describe {
                    summary = "List prompt templates"
                    description = "The teacher's templates; lessonType/level filters also match templates without that field."
                    responses { HttpStatusCode.OK { schema = jsonSchema<List<PromptTemplateResponse>>() } }
                }

                post<PromptTemplateInput> {
                    call.respond(HttpStatusCode.Created, templates.create(it, call.requirePrincipal()))
                }.describe {
                    summary = "Create prompt template"
                    requestBody { schema = jsonSchema<PromptTemplateInput>() }
                    responses {
                        HttpStatusCode.Created { schema = jsonSchema<PromptTemplateResponse>() }
                        HttpStatusCode.Conflict { description = "PROMPT_TEMPLATE_DUPLICATE"; schema = jsonSchema<ProblemDetail>() }
                    }
                }

                route("/{id}") {
                    get {
                        call.respond(HttpStatusCode.OK, templates.get(call.getPathUUID(), call.requirePrincipal()))
                    }.describe {
                        summary = "Get prompt template"
                        parameters { path("id") { description = "Template UUID" } }
                        responses {
                            HttpStatusCode.OK { schema = jsonSchema<PromptTemplateResponse>() }
                            HttpStatusCode.NotFound { description = "PROMPT_TEMPLATE_NOT_FOUND"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    put<PromptTemplateInput> {
                        call.respond(HttpStatusCode.OK, templates.update(call.getPathUUID(), it, call.requirePrincipal()))
                    }.describe {
                        summary = "Replace prompt template"
                        parameters { path("id") { description = "Template UUID" } }
                        requestBody { schema = jsonSchema<PromptTemplateInput>() }
                        responses { HttpStatusCode.OK { schema = jsonSchema<PromptTemplateResponse>() } }
                    }

                    delete {
                        templates.delete(call.getPathUUID(), call.requirePrincipal())
                        call.respond(HttpStatusCode.NoContent)
                    }.describe {
                        summary = "Delete prompt template"
                        parameters { path("id") { description = "Template UUID" } }
                        responses { HttpStatusCode.NoContent { description = "Deleted" } }
                    }
                }
            }

            get("/api/v1/ai/status") {
                LibraryAccess.requireTeacher(call.requirePrincipal())
                call.respond(HttpStatusCode.OK, AiStatusResponse(available = aiRuntime.gateway != null))
            }.describe {
                summary = "AI availability"
                description = "Whether AI features (generate, fill-missing, material tag suggestions) are configured. Teacher only."
                responses { HttpStatusCode.OK { schema = jsonSchema<AiStatusResponse>() } }
            }

            post<FillMissingRequest>("/api/v1/vocab-entries/fill-missing") {
                call.respond(HttpStatusCode.Accepted, fillService.start(it, call.requirePrincipal()))
            }.describe {
                summary = "Fill missing translations"
                description = "Queues a FILL_TRANSLATIONS job for the teacher's entries. The job only proposes values for empty fields; " +
                        "apply them with POST /api/v1/ai/jobs/{id}/fill-proposals/apply."
                requestBody { schema = jsonSchema<FillMissingRequest>() }
                responses {
                    HttpStatusCode.Accepted { schema = jsonSchema<GenerationJobResponse>() }
                    HttpStatusCode.ServiceUnavailable { description = "AI_NOT_CONFIGURED"; schema = jsonSchema<ProblemDetail>() }
                }
            }

            get("/api/v1/students/{studentId}/vocab/missing-fields") {
                val fields = call.request.queryParameters["fields"].orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if (fields.isEmpty()) throw com.gvart.parleyroom.common.transfer.exception.BadRequestException(
                    "fields is required", code = "VALIDATION_FAILED",
                )
                call.respond(HttpStatusCode.OK, fillService.missingFields(call.getPathUUID("studentId"), fields, call.requirePrincipal()))
            }.describe {
                summary = "Student words missing display fields"
                description = "Entries of the teacher's library assigned to the student that lack any of the requested fields."
                parameters { path("studentId") { description = "Student UUID" } }
                responses { HttpStatusCode.OK { schema = jsonSchema<MissingFieldsResponse>() } }
            }
        }
    }
}
