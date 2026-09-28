package com.gvart.parleyroom.library.routing

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.routing.getPathUUID
import com.gvart.parleyroom.common.routing.requirePrincipal
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.library.service.LibraryService
import com.gvart.parleyroom.library.transfer.GrammarLevelGroup
import com.gvart.parleyroom.library.transfer.GrammarTopicLibrary
import com.gvart.parleyroom.library.transfer.LibrarySummary
import com.gvart.parleyroom.library.transfer.TopicLibrary
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureLibraryRouting() {
    val libraryService: LibraryService by dependencies

    routing {
        authenticate {
            route("/api/v1/library") {
                get("/summary") {
                    call.respond(HttpStatusCode.OK, libraryService.summary(call.requirePrincipal(), call.levelParam()))
                }.describe {
                    summary = "Library summary"
                    description = "Counts per level and per topic (direct tags + subtree totals) of the teacher's library. Teacher only."
                    parameters { query("level") { description = "Restrict per-topic word/document/material counts to this level"; required = false } }
                    responses {
                        HttpStatusCode.OK { schema = jsonSchema<LibrarySummary>() }
                        HttpStatusCode.Forbidden { schema = jsonSchema<ProblemDetail>() }
                    }
                }

                get("/topics/{id}") {
                    call.respond(HttpStatusCode.OK, libraryService.topic(call.getPathUUID(), call.requirePrincipal(), call.levelParam()))
                }.describe {
                    summary = "Topic library"
                    description = "A topic with its path, children, words, documents, materials, lessons and the students who covered it. Teacher only."
                    parameters {
                        path("id") { description = "Topic UUID" }
                        query("level") { description = "Only words/documents/materials of this level"; required = false }
                    }
                    responses {
                        HttpStatusCode.OK { schema = jsonSchema<TopicLibrary>() }
                        HttpStatusCode.NotFound { description = "TOPIC_NOT_FOUND"; schema = jsonSchema<ProblemDetail>() }
                    }
                }

                get("/grammar") {
                    call.respond(HttpStatusCode.OK, libraryService.grammarChecklist(call.requirePrincipal(), call.levelParam()))
                }.describe {
                    summary = "Grammar checklist"
                    description = "Grammar topics grouped by level in checklist order, with document/material/lesson/covered-student counts. Teacher only."
                    parameters { query("level") { description = "Only this level's group"; required = false } }
                    responses { HttpStatusCode.OK { schema = jsonSchema<List<GrammarLevelGroup>>() } }
                }

                get("/grammar/{id}") {
                    call.respond(HttpStatusCode.OK, libraryService.grammarTopic(call.getPathUUID(), call.requirePrincipal()))
                }.describe {
                    summary = "Grammar topic library"
                    description = "A grammar topic with its documents, materials, lessons and the students who covered it. Teacher only."
                    parameters { path("id") { description = "Grammar topic UUID" } }
                    responses {
                        HttpStatusCode.OK { schema = jsonSchema<GrammarTopicLibrary>() }
                        HttpStatusCode.NotFound { description = "GRAMMAR_TOPIC_NOT_FOUND"; schema = jsonSchema<ProblemDetail>() }
                    }
                }
            }
        }
    }
}

private fun ApplicationCall.levelParam(): LanguageLevel? = request.queryParameters["level"]?.let(LanguageLevel::valueOf)
