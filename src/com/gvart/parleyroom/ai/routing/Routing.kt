package com.gvart.parleyroom.ai.routing

import com.gvart.parleyroom.topic.service.LibraryAccess
import com.gvart.parleyroom.ai.transfer.AiStatusResponse
import com.gvart.parleyroom.ai.config.AiRuntime
import com.gvart.parleyroom.ai.data.PromptTemplateLessonType
import com.gvart.parleyroom.ai.service.FillTranslationsService
import com.gvart.parleyroom.ai.service.LibrarySuggestionService
import com.gvart.parleyroom.ai.service.NachbereitungPublishService
import com.gvart.parleyroom.ai.service.NachbereitungService
import com.gvart.parleyroom.ai.service.PromptTemplateService
import com.gvart.parleyroom.ai.transfer.FillMissingRequest
import com.gvart.parleyroom.ai.transfer.GenerateRequest
import com.gvart.parleyroom.ai.transfer.GenerationJobResponse
import com.gvart.parleyroom.ai.transfer.LibrarySuggestions
import com.gvart.parleyroom.ai.transfer.MissingFieldsResponse
import com.gvart.parleyroom.ai.transfer.NachbereitungState
import com.gvart.parleyroom.ai.transfer.PromptTemplateInput
import com.gvart.parleyroom.ai.transfer.PromptTemplateResponse
import com.gvart.parleyroom.ai.transfer.PublishRequest
import com.gvart.parleyroom.ai.transfer.PublishResponse
import com.gvart.parleyroom.ai.transfer.RefineRequest
import com.gvart.parleyroom.ai.transfer.ReviewUpdateRequest
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
    val nachbereitung: NachbereitungService by dependencies
    val publishService: NachbereitungPublishService by dependencies
    val templates: PromptTemplateService by dependencies
    val suggestions: LibrarySuggestionService by dependencies
    val fillService: FillTranslationsService by dependencies
    val aiRuntime: AiRuntime by dependencies

    routing {
        authenticate {
            route("/api/v1/lessons/{id}") {
                get("/nachbereitung") {
                    call.respond(HttpStatusCode.OK, nachbereitung.state(call.getPathUUID(), call.requirePrincipal()))
                }.describe {
                    summary = "Nachbereitung panel state"
                    description = "Mode, prefilled notes and prompt, the context the server adds, the latest job and the draft. Lesson teacher or admin."
                    parameters { path("id") { description = "Lesson UUID" } }
                    responses { HttpStatusCode.OK { schema = jsonSchema<NachbereitungState>() } }
                }

                post<GenerateRequest>("/nachbereitung/generate") {
                    call.respond(HttpStatusCode.Accepted, nachbereitung.generate(call.getPathUUID(), it, call.requirePrincipal()))
                }.describe {
                    summary = "Start AI generation"
                    description = "Queues a GENERATE job; poll GET /api/v1/ai/jobs/{id}. On success a draft document exists."
                    parameters { path("id") { description = "Lesson UUID" } }
                    requestBody { schema = jsonSchema<GenerateRequest>() }
                    responses {
                        HttpStatusCode.Accepted { schema = jsonSchema<GenerationJobResponse>() }
                        HttpStatusCode.BadRequest { description = "NACHBEREITUNG_NO_ATTENDEES, VALIDATION_FAILED"; schema = jsonSchema<ProblemDetail>() }
                        HttpStatusCode.TooManyRequests { description = "AI_RATE_LIMITED"; schema = jsonSchema<ProblemDetail>() }
                        HttpStatusCode.ServiceUnavailable { description = "AI_NOT_CONFIGURED"; schema = jsonSchema<ProblemDetail>() }
                    }
                }

                post<PublishRequest>("/nachbereitung/publish") {
                    call.respond(HttpStatusCode.OK, publishService.publish(call.getPathUUID(), it, call.requirePrincipal()))
                }.describe {
                    summary = "Publish Nachbereitung"
                    description = "One transaction: words to the library and the learners, accepted topics/grammar, lesson content, " +
                            "vocab tables filled, document linked and shared. share=false saves to the library only " +
                            "(nothing assigned, linked or shared). Idempotent."
                    parameters { path("id") { description = "Lesson UUID" } }
                    requestBody { schema = jsonSchema<PublishRequest>() }
                    responses {
                        HttpStatusCode.OK { schema = jsonSchema<PublishResponse>() }
                        HttpStatusCode.Conflict { description = "AI_JOB_NOT_READY"; schema = jsonSchema<ProblemDetail>() }
                    }
                }

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
                    call.respond(HttpStatusCode.OK, nachbereitung.getJob(call.getPathUUID(), call.requirePrincipal()))
                }.describe {
                    summary = "Get AI job"
                    description = "Poll until status is SUCCEEDED or FAILED."
                    parameters { path("id") { description = "Job UUID" } }
                    responses {
                        HttpStatusCode.OK { schema = jsonSchema<GenerationJobResponse>() }
                        HttpStatusCode.NotFound { description = "AI_JOB_NOT_FOUND"; schema = jsonSchema<ProblemDetail>() }
                    }
                }

                post<RefineRequest>("/refine") {
                    call.respond(HttpStatusCode.Accepted, nachbereitung.refine(call.getPathUUID(), it, call.requirePrincipal()))
                }.describe {
                    summary = "Refine a Nachbereitung"
                    description = "Queues a REFINE job on a SUCCEEDED job; on success the draft's blocks are replaced (snapshot AI_REFINE)."
                    parameters { path("id") { description = "Job UUID" } }
                    requestBody { schema = jsonSchema<RefineRequest>() }
                    responses {
                        HttpStatusCode.Accepted { schema = jsonSchema<GenerationJobResponse>() }
                        HttpStatusCode.Conflict { description = "AI_JOB_NOT_READY"; schema = jsonSchema<ProblemDetail>() }
                    }
                }

                put<ReviewUpdateRequest>("/review") {
                    call.respond(HttpStatusCode.OK, nachbereitung.updateReview(call.getPathUUID(), it, call.requirePrincipal()))
                }.describe {
                    summary = "Save the vocab review"
                    description = "Persists checkboxes and inline edits of the review table into the job result."
                    parameters { path("id") { description = "Job UUID" } }
                    requestBody { schema = jsonSchema<ReviewUpdateRequest>() }
                    responses { HttpStatusCode.OK { schema = jsonSchema<GenerationJobResponse>() } }
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
                description = "Queues a FILL_TRANSLATIONS job for the teacher's entries; only empty fields are filled."
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
