package com.gvart.parleyroom.topic.routing

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.routing.getPathUUID
import com.gvart.parleyroom.common.routing.requirePrincipal
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.topic.service.GrammarTopicService
import com.gvart.parleyroom.topic.service.TopicService
import com.gvart.parleyroom.topic.transfer.CreateTopicRequest
import com.gvart.parleyroom.topic.transfer.GrammarTopicRequest
import com.gvart.parleyroom.topic.transfer.GrammarTopicResponse
import com.gvart.parleyroom.topic.transfer.TopicResponse
import com.gvart.parleyroom.topic.transfer.UpdateTopicRequest
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

fun Application.configureTopicRouting() {
    val topicService: TopicService by dependencies
    val grammarTopicService: GrammarTopicService by dependencies

    routing {
        authenticate {
            route("/api/v1/topics") {
                get {
                    call.respond(HttpStatusCode.OK, topicService.listTopics(call.requirePrincipal()))
                }.describe {
                    summary = "List topics"
                    description = "Flat list of the teacher's topic tree (build the tree from parentId). Students get their teachers' topics, admins get all."
                    responses { HttpStatusCode.OK { schema = jsonSchema<List<TopicResponse>>() } }
                }

                post<CreateTopicRequest> {
                    call.respond(HttpStatusCode.Created, topicService.createTopic(it, call.requirePrincipal()))
                }.describe {
                    summary = "Create topic"
                    description = "Creates a topic in the teacher's library, optionally under a parent topic. Teacher only."
                    requestBody { schema = jsonSchema<CreateTopicRequest>() }
                    responses {
                        HttpStatusCode.Created { schema = jsonSchema<TopicResponse>() }
                        HttpStatusCode.Conflict { description = "TOPIC_DUPLICATE"; schema = jsonSchema<ProblemDetail>() }
                    }
                }

                route("/{id}") {
                    patch<UpdateTopicRequest> {
                        val result = topicService.updateTopic(call.getPathUUID(), it, call.requirePrincipal())
                        call.respond(HttpStatusCode.OK, result)
                    }.describe {
                        summary = "Update topic"
                        description = "Renames, re-levels or moves a topic. Use moveToRoot=true to detach from its parent."
                        requestBody { schema = jsonSchema<UpdateTopicRequest>() }
                        parameters { path("id") { description = "Topic UUID" } }
                        responses {
                            HttpStatusCode.OK { schema = jsonSchema<TopicResponse>() }
                            HttpStatusCode.BadRequest { description = "TOPIC_CYCLE"; schema = jsonSchema<ProblemDetail>() }
                            HttpStatusCode.Conflict { description = "TOPIC_DUPLICATE"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    delete {
                        topicService.deleteTopic(call.getPathUUID(), call.requirePrincipal())
                        call.respond(HttpStatusCode.NoContent)
                    }.describe {
                        summary = "Delete topic"
                        description = "Deletes a topic. Fails with TOPIC_HAS_CHILDREN if it still has sub-topics. Tag links (vocab, lessons, materials) are removed."
                        parameters { path("id") { description = "Topic UUID" } }
                        responses {
                            HttpStatusCode.NoContent { description = "Deleted" }
                            HttpStatusCode.Conflict { description = "TOPIC_HAS_CHILDREN"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }
                }
            }

            route("/api/v1/grammar-topics") {
                get {
                    val level = call.request.queryParameters["level"]?.let(LanguageLevel::valueOf)
                    call.respond(HttpStatusCode.OK, grammarTopicService.listGrammarTopics(call.requirePrincipal(), level))
                }.describe {
                    summary = "List grammar topics"
                    description = "The teacher's grammar topics ordered by level then name. Students get their teachers' grammar topics."
                    parameters { query("level") { description = "Filter by level (A1..C2)"; required = false } }
                    responses { HttpStatusCode.OK { schema = jsonSchema<List<GrammarTopicResponse>>() } }
                }

                post<GrammarTopicRequest> {
                    call.respond(HttpStatusCode.Created, grammarTopicService.createGrammarTopic(it, call.requirePrincipal()))
                }.describe {
                    summary = "Create grammar topic"
                    requestBody { schema = jsonSchema<GrammarTopicRequest>() }
                    responses {
                        HttpStatusCode.Created { schema = jsonSchema<GrammarTopicResponse>() }
                        HttpStatusCode.Conflict { description = "GRAMMAR_TOPIC_DUPLICATE"; schema = jsonSchema<ProblemDetail>() }
                    }
                }

                route("/{id}") {
                    get {
                        call.respond(HttpStatusCode.OK, grammarTopicService.getGrammarTopic(call.getPathUUID(), call.requirePrincipal()))
                    }.describe {
                        summary = "Get grammar topic"
                        parameters { path("id") { description = "Grammar topic UUID" } }
                        responses { HttpStatusCode.OK { schema = jsonSchema<GrammarTopicResponse>() } }
                    }

                    put<GrammarTopicRequest> {
                        val result = grammarTopicService.updateGrammarTopic(call.getPathUUID(), it, call.requirePrincipal())
                        call.respond(HttpStatusCode.OK, result)
                    }.describe {
                        summary = "Replace grammar topic"
                        requestBody { schema = jsonSchema<GrammarTopicRequest>() }
                        parameters { path("id") { description = "Grammar topic UUID" } }
                        responses { HttpStatusCode.OK { schema = jsonSchema<GrammarTopicResponse>() } }
                    }

                    delete {
                        grammarTopicService.deleteGrammarTopic(call.getPathUUID(), call.requirePrincipal())
                        call.respond(HttpStatusCode.NoContent)
                    }.describe {
                        summary = "Delete grammar topic"
                        parameters { path("id") { description = "Grammar topic UUID" } }
                        responses { HttpStatusCode.NoContent { description = "Deleted" } }
                    }
                }
            }
        }
    }
}
