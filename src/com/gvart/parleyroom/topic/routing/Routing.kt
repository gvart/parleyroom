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
import com.gvart.parleyroom.topic.transfer.MergeRequest
import com.gvart.parleyroom.topic.transfer.ReorderGrammarTopicsRequest
import com.gvart.parleyroom.topic.transfer.TopicResponse
import com.gvart.parleyroom.topic.transfer.UpdateTopicRequest
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
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
                        topicService.deleteTopic(call.getPathUUID(), call.requirePrincipal(), call.forceParam())
                        call.respond(HttpStatusCode.NoContent)
                    }.describe {
                        summary = "Delete topic"
                        description = "Deletes a topic. Fails with TOPIC_HAS_CHILDREN if it still has sub-topics, and with TOPIC_HAS_CONTENT (+ usage) if anything is tagged with it unless force=true, which removes the tags (never the content)."
                        parameters {
                            path("id") { description = "Topic UUID" }
                            query("force") { description = "true to remove the tags and delete anyway"; required = false }
                        }
                        responses {
                            HttpStatusCode.NoContent { description = "Deleted" }
                            HttpStatusCode.Conflict { description = "TOPIC_HAS_CHILDREN, TOPIC_HAS_CONTENT"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    post<MergeRequest>("/merge") {
                        val sourceId = call.getPathUUID()
                        val principal = call.requirePrincipal()
                        if (call.dryRunParam()) call.respond(HttpStatusCode.OK, topicService.previewMerge(sourceId, it, principal))
                        else call.respond(HttpStatusCode.OK, topicService.merge(sourceId, it, principal))
                    }.describe {
                        summary = "Merge topic"
                        description = "Merges this topic into targetId: tags re-pointed (deduped), children moved (same-name children merged recursively), topic deleted. dryRun=true returns a MergePreview without changes."
                        requestBody { schema = jsonSchema<MergeRequest>() }
                        parameters {
                            path("id") { description = "Topic UUID (merged away)" }
                            query("dryRun") { description = "true to only preview the counts"; required = false }
                        }
                        responses {
                            HttpStatusCode.OK { description = "Topic (target) or MergePreview (dryRun)"; schema = jsonSchema<TopicResponse>() }
                            HttpStatusCode.BadRequest { description = "TOPIC_MERGE_INVALID"; schema = jsonSchema<ProblemDetail>() }
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

                put<ReorderGrammarTopicsRequest>("/order") {
                    call.respond(HttpStatusCode.OK, grammarTopicService.reorder(it, call.requirePrincipal()))
                }.describe {
                    summary = "Reorder grammar checklist"
                    description = "ids must be exactly the teacher's grammar topics of the level (null = without level); positions become the list index."
                    requestBody { schema = jsonSchema<ReorderGrammarTopicsRequest>() }
                    responses {
                        HttpStatusCode.OK { schema = jsonSchema<List<GrammarTopicResponse>>() }
                        HttpStatusCode.BadRequest { description = "GRAMMAR_ORDER_INVALID"; schema = jsonSchema<ProblemDetail>() }
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
                        grammarTopicService.deleteGrammarTopic(call.getPathUUID(), call.requirePrincipal(), call.forceParam())
                        call.respond(HttpStatusCode.NoContent)
                    }.describe {
                        summary = "Delete grammar topic"
                        description = "Fails with GRAMMAR_TOPIC_HAS_CONTENT (+ usage) if anything is tagged with it unless force=true, which removes the tags."
                        parameters {
                            path("id") { description = "Grammar topic UUID" }
                            query("force") { description = "true to remove the tags and delete anyway"; required = false }
                        }
                        responses {
                            HttpStatusCode.NoContent { description = "Deleted" }
                            HttpStatusCode.Conflict { description = "GRAMMAR_TOPIC_HAS_CONTENT"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    post<MergeRequest>("/merge") {
                        val sourceId = call.getPathUUID()
                        val principal = call.requirePrincipal()
                        if (call.dryRunParam()) call.respond(HttpStatusCode.OK, grammarTopicService.previewMerge(sourceId, it, principal))
                        else call.respond(HttpStatusCode.OK, grammarTopicService.merge(sourceId, it, principal))
                    }.describe {
                        summary = "Merge grammar topic"
                        description = "Merges this grammar topic into targetId (tags re-pointed and deduped, then deleted). dryRun=true returns a MergePreview without changes."
                        requestBody { schema = jsonSchema<MergeRequest>() }
                        parameters {
                            path("id") { description = "Grammar topic UUID (merged away)" }
                            query("dryRun") { description = "true to only preview the counts"; required = false }
                        }
                        responses {
                            HttpStatusCode.OK { description = "GrammarTopic (target) or MergePreview (dryRun)"; schema = jsonSchema<GrammarTopicResponse>() }
                            HttpStatusCode.BadRequest { description = "GRAMMAR_TOPIC_MERGE_INVALID"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }
                }
            }
        }
    }
}

private fun ApplicationCall.forceParam() = request.queryParameters["force"]?.toBoolean() ?: false

private fun ApplicationCall.dryRunParam() = request.queryParameters["dryRun"]?.toBoolean() ?: false
