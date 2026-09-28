package com.gvart.parleyroom.practice.routing

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.routing.getPathUUID
import com.gvart.parleyroom.common.routing.getQueryUUID
import com.gvart.parleyroom.common.routing.requirePrincipal
import com.gvart.parleyroom.common.transfer.PageRequest
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.practice.data.PracticeMode
import com.gvart.parleyroom.practice.service.PracticeService
import com.gvart.parleyroom.practice.service.SentenceService
import com.gvart.parleyroom.practice.transfer.ArticleCheckRequest
import com.gvart.parleyroom.practice.transfer.ArticleCheckResponse
import com.gvart.parleyroom.practice.transfer.CreateSentenceRequest
import com.gvart.parleyroom.practice.transfer.PracticeQueueResponse
import com.gvart.parleyroom.practice.transfer.PracticeStatsResponse
import com.gvart.parleyroom.practice.transfer.ReviewRequest
import com.gvart.parleyroom.practice.transfer.SentencePageResponse
import com.gvart.parleyroom.practice.transfer.SentenceResponse
import com.gvart.parleyroom.vocabulary.transfer.StudentVocabResponse
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configurePracticeRouting() {
    val practiceService: PracticeService by dependencies
    val sentenceService: SentenceService by dependencies

    routing {
        authenticate {
            route("/api/v1/practice") {
                get("/queue") {
                    val params = call.request.queryParameters
                    val mode = params["mode"]?.let { raw ->
                        PracticeMode.entries.firstOrNull { it.name == raw }
                            ?: throw BadRequestException("Unknown practice mode: $raw", code = "PRACTICE_MODE_INVALID")
                    } ?: PracticeMode.DE_TO_MEANING
                    val filters = PracticeService.Filters(
                        topicId = call.getQueryUUID("topicId"),
                        lessonId = call.getQueryUUID("lessonId"),
                        level = params["level"]?.let(LanguageLevel::valueOf),
                    )
                    val limit = (params["limit"]?.toIntOrNull() ?: PracticeService.DEFAULT_QUEUE_LIMIT)
                        .coerceIn(1, PracticeService.MAX_QUEUE_LIMIT)
                    call.respond(HttpStatusCode.OK, practiceService.queue(call.requirePrincipal(), mode, filters, limit))
                }.describe {
                    summary = "Practice queue"
                    description = "Student only. Due cards first (oldest due), then new cards up to the daily new-card limit. " +
                            "Cards respect the vocab display setting; ARTICLE mode hides article, plural, forms and example. " +
                            "`intervals` previews the next due date per rating."
                    parameters {
                        query("mode") { description = "DE_TO_MEANING (default), MEANING_TO_DE, ARTICLE"; required = false }
                        query("topicId") { description = "Topic UUID (includes subtopics)"; required = false }
                        query("lessonId") { description = "Words received in this lesson"; required = false }
                        query("level") { description = "Entry level (A1..C2)"; required = false }
                        query("limit") { description = "Cards to return (1..100, default 20)"; required = false }
                    }
                    responses {
                        HttpStatusCode.OK { schema = jsonSchema<PracticeQueueResponse>() }
                        HttpStatusCode.BadRequest { description = "PRACTICE_MODE_INVALID"; schema = jsonSchema<ProblemDetail>() }
                        HttpStatusCode.Forbidden { description = "PRACTICE_STUDENT_ONLY"; schema = jsonSchema<ProblemDetail>() }
                    }
                }

                get("/stats") {
                    call.respond(HttpStatusCode.OK, practiceService.stats(call.getQueryUUID("studentId"), call.requirePrincipal()))
                }.describe {
                    summary = "Practice stats"
                    description = "Dashboard numbers: due now/today, new available, reviewed today, sentences today, " +
                            "aiAvailable and the streak. Students get their own; teachers and admins pass studentId."
                    parameters { query("studentId") { description = "Student UUID (teachers/admins, required)"; required = false } }
                    responses { HttpStatusCode.OK { schema = jsonSchema<PracticeStatsResponse>() } }
                }
            }

            route("/api/v1/vocabulary/{id}") {
                post("/review") {
                    val (id, principal) = call.getPathUUID() to call.requirePrincipal()
                    practiceService.requireOwnWord(id, principal)
                    val body = call.receive<ReviewRequest>()
                    val result = practiceService.review(id, body.rating, body.mode, body.responseMs, principal)
                    call.respond(HttpStatusCode.OK, result)
                }.describe {
                    summary = "Review word (FSRS)"
                    description = "Student only, own word. Grades the FSRS card (AGAIN, HARD, GOOD, EASY), schedules the next " +
                            "review, derives the status and counts for the streak. mode ARTICLE is rejected (use /article)."
                    parameters { path("id") { description = "Student vocab UUID" } }
                    requestBody { schema = jsonSchema<ReviewRequest>() }
                    responses {
                        HttpStatusCode.OK { schema = jsonSchema<StudentVocabResponse>() }
                        HttpStatusCode.BadRequest { description = "PRACTICE_MODE_INVALID, VALIDATION_FAILED"; schema = jsonSchema<ProblemDetail>() }
                        HttpStatusCode.Forbidden { description = "PRACTICE_STUDENT_ONLY"; schema = jsonSchema<ProblemDetail>() }
                    }
                }

                post("/article") {
                    val (id, principal) = call.getPathUUID() to call.requirePrincipal()
                    practiceService.requireOwnWord(id, principal)
                    val body = call.receive<ArticleCheckRequest>()
                    val result = practiceService.checkArticle(id, body.article, body.responseMs, principal)
                    call.respond(HttpStatusCode.OK, result)
                }.describe {
                    summary = "Check article (der/die/das)"
                    description = "Student only, own noun. Correct -> GOOD, wrong -> AGAIN on the word's FSRS card."
                    parameters { path("id") { description = "Student vocab UUID" } }
                    requestBody { schema = jsonSchema<ArticleCheckRequest>() }
                    responses {
                        HttpStatusCode.OK { schema = jsonSchema<ArticleCheckResponse>() }
                        HttpStatusCode.BadRequest { description = "NOT_A_NOUN, VALIDATION_FAILED"; schema = jsonSchema<ProblemDetail>() }
                        HttpStatusCode.Forbidden { description = "PRACTICE_STUDENT_ONLY"; schema = jsonSchema<ProblemDetail>() }
                    }
                }

                route("/sentences") {
                    post {
                        val (id, principal) = call.getPathUUID() to call.requirePrincipal()
                        practiceService.requireOwnWord(id, principal)
                        val body = call.receive<CreateSentenceRequest>()
                        call.respond(HttpStatusCode.Created, sentenceService.create(id, body.sentence, principal))
                    }.describe {
                        summary = "Write own sentence"
                        description = "Student only, own word. Synchronous AI feedback (corrected sentence, one-line explanation). " +
                                "Only the sentence, the word data, the level and a language code are sent to the AI."
                        parameters { path("id") { description = "Student vocab UUID" } }
                        requestBody { schema = jsonSchema<CreateSentenceRequest>() }
                        responses {
                            HttpStatusCode.Created { schema = jsonSchema<SentenceResponse>() }
                            HttpStatusCode.BadRequest { description = "SENTENCE_EMPTY, SENTENCE_TOO_LONG"; schema = jsonSchema<ProblemDetail>() }
                            HttpStatusCode.TooManyRequests { description = "PRACTICE_SENTENCE_LIMIT (daily cap, with resetsAt) or AI_RATE_LIMITED (provider)"; schema = jsonSchema<ProblemDetail>() }
                            HttpStatusCode.ServiceUnavailable { description = "AI_NOT_CONFIGURED, AI_PROVIDER_ERROR, AI_OUTPUT_INVALID, AI_TIMEOUT"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    get {
                        call.respond(HttpStatusCode.OK, sentenceService.listForWord(call.getPathUUID(), call.requirePrincipal()))
                    }.describe {
                        summary = "List sentences for a word"
                        description = "Newest first. The student, their teachers and admins."
                        parameters { path("id") { description = "Student vocab UUID" } }
                        responses { HttpStatusCode.OK { schema = jsonSchema<List<SentenceResponse>>() } }
                    }
                }
            }

            get("/api/v1/students/{studentId}/sentences") {
                val result = sentenceService.listForStudent(call.getPathUUID("studentId"), call.requirePrincipal(), PageRequest.from(call))
                call.respond(HttpStatusCode.OK, result)
            }.describe {
                summary = "List a student's sentences"
                description = "All own sentences of the student with AI feedback, newest first. The student, their teachers and admins."
                parameters {
                    path("studentId") { description = "Student UUID" }
                    query("page") { description = "Page number (1-based, default 1)"; required = false }
                    query("pageSize") { description = "Items per page (default 20, max 100)"; required = false }
                }
                responses { HttpStatusCode.OK { schema = jsonSchema<SentencePageResponse>() } }
            }
        }
    }
}
