package com.gvart.parleyroom.vocabulary.routing

import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.vocabulary.data.VocabStatus
import com.gvart.parleyroom.vocabulary.service.VocabularyService
import com.gvart.parleyroom.vocabulary.transfer.CreateVocabularyWordRequest
import com.gvart.parleyroom.vocabulary.transfer.UpdateVocabularyWordRequest
import com.gvart.parleyroom.vocabulary.transfer.VocabularyWordResponse
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

fun Application.configureVocabularyRouting() {
    val vocabularyService: VocabularyService by dependencies

    routing {
        authenticate {
            route("/api/v1/vocabulary") {
                get {
                    val principal = call.principal<UserPrincipal>()!!
                    val studentId = call.queryParameters["studentId"]?.let(UUID::fromString)
                    val status = call.queryParameters["status"]?.let { VocabStatus.valueOf(it) }

                    val result = vocabularyService.getWords(principal, studentId, status)
                    call.respond(HttpStatusCode.OK, result)
                }.describe {
                    summary = "Get vocabulary words"
                    description = "Lists vocabulary words. Students see their own, teachers see their students', admins see all. Supports filtering by studentId and status."
                    parameters {
                        query("studentId") { description = "Filter by student UUID"; required = false }
                        query("status") { description = "Filter by status (NEW, REVIEW, LEARNED)"; required = false }
                    }
                    responses {
                        HttpStatusCode.OK {
                            description = "List of vocabulary words"
                            schema = jsonSchema<List<VocabularyWordResponse>>()
                        }
                    }
                }

                post<CreateVocabularyWordRequest> {
                    val principal = call.principal<UserPrincipal>()!!

                    val result = vocabularyService.createWord(it, principal)
                    call.respond(HttpStatusCode.Created, result)
                }.describe {
                    summary = "Add vocabulary word"
                    description = "Adds a new vocabulary word for a student."
                    requestBody { schema = jsonSchema<CreateVocabularyWordRequest>() }
                    responses {
                        HttpStatusCode.Created {
                            description = "Word added"
                            schema = jsonSchema<VocabularyWordResponse>()
                        }
                        HttpStatusCode.Conflict {
                            description = "Word already exists for this student"
                            schema = jsonSchema<ProblemDetail>()
                        }
                    }
                }

                route("/{id}") {
                    get {
                        val principal = call.principal<UserPrincipal>()!!
                        val id = UUID.fromString(call.parameters["id"])

                        val result = vocabularyService.getWord(id, principal)
                        call.respond(HttpStatusCode.OK, result)
                    }.describe {
                        summary = "Get vocabulary word"
                        description = "Gets a single vocabulary word by ID."
                        parameters { path("id") { description = "UUID of the vocabulary word" } }
                        responses {
                            HttpStatusCode.OK {
                                description = "Vocabulary word"
                                schema = jsonSchema<VocabularyWordResponse>()
                            }
                            HttpStatusCode.NotFound {
                                description = "Word not found"
                                schema = jsonSchema<ProblemDetail>()
                            }
                        }
                    }

                    put<UpdateVocabularyWordRequest> {
                        val principal = call.principal<UserPrincipal>()!!
                        val id = UUID.fromString(call.parameters["id"])

                        val result = vocabularyService.updateWord(id, it, principal)
                        call.respond(HttpStatusCode.OK, result)
                    }.describe {
                        summary = "Update vocabulary word"
                        description = "Updates fields of a vocabulary word."
                        requestBody { schema = jsonSchema<UpdateVocabularyWordRequest>() }
                        parameters { path("id") { description = "UUID of the vocabulary word" } }
                        responses {
                            HttpStatusCode.OK {
                                description = "Word updated"
                                schema = jsonSchema<VocabularyWordResponse>()
                            }
                        }
                    }

                    delete {
                        val principal = call.principal<UserPrincipal>()!!
                        val id = UUID.fromString(call.parameters["id"])

                        vocabularyService.deleteWord(id, principal)
                        call.respond(HttpStatusCode.NoContent)
                    }.describe {
                        summary = "Delete vocabulary word"
                        description = "Deletes a vocabulary word."
                        parameters { path("id") { description = "UUID of the vocabulary word" } }
                        responses {
                            HttpStatusCode.NoContent { description = "Word deleted" }
                            HttpStatusCode.NotFound {
                                description = "Word not found"
                                schema = jsonSchema<ProblemDetail>()
                            }
                        }
                    }

                    post("/review") {
                        val principal = call.principal<UserPrincipal>()!!
                        val id = UUID.fromString(call.parameters["id"])

                        val result = vocabularyService.reviewWord(id, principal)
                        call.respond(HttpStatusCode.OK, result)
                    }.describe {
                        summary = "Review vocabulary word"
                        description = "Marks a word as reviewed, incrementing review count and scheduling next review using spaced repetition."
                        parameters { path("id") { description = "UUID of the vocabulary word" } }
                        responses {
                            HttpStatusCode.OK {
                                description = "Word reviewed"
                                schema = jsonSchema<VocabularyWordResponse>()
                            }
                        }
                    }
                }
            }
        }
    }
}
