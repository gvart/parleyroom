package com.gvart.parleyroom.document.routing

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.routing.getPathUUID
import com.gvart.parleyroom.common.routing.getQueryUUID
import com.gvart.parleyroom.common.routing.requirePrincipal
import com.gvart.parleyroom.common.transfer.PageRequest
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.document.data.DocumentAudience
import com.gvart.parleyroom.document.service.DocumentBlockValidator
import com.gvart.parleyroom.document.service.DocumentService
import com.gvart.parleyroom.document.service.DocumentVersionService
import com.gvart.parleyroom.document.transfer.CreateDocumentRequest
import com.gvart.parleyroom.document.transfer.DocumentPageResponse
import com.gvart.parleyroom.document.transfer.DocumentResponse
import com.gvart.parleyroom.document.transfer.DocumentShareRequest
import com.gvart.parleyroom.document.transfer.DocumentSummary
import com.gvart.parleyroom.document.transfer.DocumentVersionResponse
import com.gvart.parleyroom.document.transfer.DocumentVersionSummary
import com.gvart.parleyroom.document.transfer.DuplicateDocumentRequest
import com.gvart.parleyroom.document.transfer.LinkLessonDocumentRequest
import com.gvart.parleyroom.document.transfer.UpdateDocumentRequest
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
import java.util.UUID

fun Application.configureDocumentRouting() {
    val documentService: DocumentService by dependencies
    val versionService: DocumentVersionService by dependencies

    routing {
        authenticate {
            route("/api/v1/documents") {
                get("/schema") {
                    call.respond(HttpStatusCode.OK, DocumentBlockValidator.schemaJson)
                }.describe {
                    summary = "Document block JSON Schema"
                    description = "JSON Schema (draft 2020-12) of a document's `blocks` array. Every write is validated against it."
                }

                get {
                    val params = call.request.queryParameters
                    val filters = DocumentService.Filters(
                        level = params["level"]?.let(LanguageLevel::valueOf),
                        topicId = call.getQueryUUID("topicId"),
                        grammarTopicId = call.getQueryUUID("grammarTopicId"),
                        audience = params["audience"]?.let(DocumentAudience::valueOf),
                        lessonId = call.getQueryUUID("lessonId"),
                        studentId = call.getQueryUUID("studentId"),
                        groupId = call.getQueryUUID("groupId"),
                        q = params["q"],
                        blockType = params["blockType"],
                    )
                    val result = documentService.listDocuments(call.requirePrincipal(), filters, PageRequest.from(call))
                    call.respond(HttpStatusCode.OK, result)
                }.describe {
                    summary = "List documents"
                    description = "Teachers: their library. Students: documents shared with them (directly, via a group, or " +
                            "linked to a lesson they attend). Admins: all. Newest first; q matches the title; blockType keeps documents containing a block of that type."
                    responses { HttpStatusCode.OK { schema = jsonSchema<DocumentPageResponse>() } }
                }

                post<CreateDocumentRequest> {
                    call.respond(HttpStatusCode.Created, documentService.createDocument(it, call.requirePrincipal()))
                }.describe {
                    summary = "Create document"
                    description = "Teacher only. Blocks are validated against GET /api/v1/documents/schema."
                    requestBody { schema = jsonSchema<CreateDocumentRequest>() }
                    responses {
                        HttpStatusCode.Created { schema = jsonSchema<DocumentResponse>() }
                        HttpStatusCode.BadRequest {
                            description = "DOCUMENT_INVALID_BLOCK (with pointer), DOCUMENT_DUPLICATE_ID, DOCUMENT_TOO_LARGE, STUDENT_NOT_LINKED"
                            schema = jsonSchema<ProblemDetail>()
                        }
                    }
                }

                route("/{id}") {
                    get {
                        call.respond(HttpStatusCode.OK, documentService.getDocument(call.getPathUUID(), call.requirePrincipal()))
                    }.describe {
                        summary = "Get document"
                        description = "Students get the document without any `solution` and without share targets."
                        parameters { path("id") { description = "Document UUID" } }
                        responses {
                            HttpStatusCode.OK { schema = jsonSchema<DocumentResponse>() }
                            HttpStatusCode.NotFound { description = "DOCUMENT_NOT_FOUND"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    put<UpdateDocumentRequest> {
                        call.respond(HttpStatusCode.OK, documentService.updateDocument(call.getPathUUID(), it, call.requirePrincipal()))
                    }.describe {
                        summary = "Replace document"
                        description = "Full replace of title, tags and blocks (the autosave target). `revision` must be the " +
                                "current one (409 DOCUMENT_CONFLICT otherwise). Snapshots the previous state when the latest " +
                                "version is at least 10 minutes old."
                        parameters { path("id") { description = "Document UUID" } }
                        requestBody { schema = jsonSchema<UpdateDocumentRequest>() }
                        responses {
                            HttpStatusCode.OK { schema = jsonSchema<DocumentResponse>() }
                            HttpStatusCode.BadRequest { description = "DOCUMENT_INVALID_BLOCK"; schema = jsonSchema<ProblemDetail>() }
                            HttpStatusCode.Conflict { description = "DOCUMENT_CONFLICT (with currentRevision)"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    delete {
                        documentService.deleteDocument(call.getPathUUID(), call.requirePrincipal())
                        call.respond(HttpStatusCode.NoContent)
                    }.describe {
                        summary = "Delete document"
                        parameters { path("id") { description = "Document UUID" } }
                        responses { HttpStatusCode.NoContent { description = "Deleted" } }
                    }

                    post<DuplicateDocumentRequest>("/duplicate") {
                        call.respond(HttpStatusCode.Created, documentService.duplicateDocument(call.getPathUUID(), it, call.requirePrincipal()))
                    }.describe {
                        summary = "Duplicate document"
                        description = "Copies content and tags with new block/item ids. The copy is not shared and not linked to lessons."
                        parameters { path("id") { description = "Source document UUID" } }
                        requestBody { schema = jsonSchema<DuplicateDocumentRequest>() }
                        responses { HttpStatusCode.Created { schema = jsonSchema<DocumentResponse>() } }
                    }

                    post<DocumentShareRequest>("/share") {
                        call.respond(HttpStatusCode.OK, documentService.share(call.getPathUUID(), it, call.requirePrincipal()))
                    }.describe {
                        summary = "Share document"
                        description = "Adds students (must be the teacher's) and groups (must be the teacher's). Snapshots the document."
                        parameters { path("id") { description = "Document UUID" } }
                        requestBody { schema = jsonSchema<DocumentShareRequest>() }
                        responses {
                            HttpStatusCode.OK { schema = jsonSchema<DocumentResponse>() }
                            HttpStatusCode.BadRequest { description = "STUDENT_NOT_LINKED"; schema = jsonSchema<ProblemDetail>() }
                            HttpStatusCode.NotFound { description = "GROUP_NOT_FOUND"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    post<DocumentShareRequest>("/unshare") {
                        call.respond(HttpStatusCode.OK, documentService.unshare(call.getPathUUID(), it, call.requirePrincipal()))
                    }.describe {
                        summary = "Unshare document"
                        parameters { path("id") { description = "Document UUID" } }
                        requestBody { schema = jsonSchema<DocumentShareRequest>() }
                        responses { HttpStatusCode.OK { schema = jsonSchema<DocumentResponse>() } }
                    }

                    route("/versions") {
                        get {
                            call.respond(HttpStatusCode.OK, versionService.listVersions(call.getPathUUID(), call.requirePrincipal()))
                        }.describe {
                            summary = "List document versions"
                            description = "Newest first; the latest 30 are kept. Owner or admin."
                            parameters { path("id") { description = "Document UUID" } }
                            responses { HttpStatusCode.OK { schema = jsonSchema<List<DocumentVersionSummary>>() } }
                        }

                        get("/{versionId}") {
                            val result = versionService.getVersion(call.getPathUUID(), call.getPathUUID("versionId"), call.requirePrincipal())
                            call.respond(HttpStatusCode.OK, result)
                        }.describe {
                            summary = "Get document version"
                            parameters {
                                path("id") { description = "Document UUID" }
                                path("versionId") { description = "Version UUID" }
                            }
                            responses {
                                HttpStatusCode.OK { schema = jsonSchema<DocumentVersionResponse>() }
                                HttpStatusCode.NotFound { description = "DOCUMENT_VERSION_NOT_FOUND"; schema = jsonSchema<ProblemDetail>() }
                            }
                        }

                        post("/{versionId}/restore") {
                            val result = versionService.restore(call.getPathUUID(), call.getPathUUID("versionId"), call.requirePrincipal())
                            call.respond(HttpStatusCode.OK, result)
                        }.describe {
                            summary = "Restore document version"
                            description = "Snapshots the current state, then replaces title and blocks with the version's."
                            parameters {
                                path("id") { description = "Document UUID" }
                                path("versionId") { description = "Version UUID" }
                            }
                            responses { HttpStatusCode.OK { schema = jsonSchema<DocumentResponse>() } }
                        }
                    }
                }
            }

            route("/api/v1/lessons/{id}/documents") {
                get {
                    call.respond(HttpStatusCode.OK, documentService.lessonDocuments(call.getPathUUID(), call.requirePrincipal()))
                }.describe {
                    summary = "List lesson documents"
                    description = "Documents linked to the lesson that the caller can read. Lesson participants and admins."
                    parameters { path("id") { description = "Lesson UUID" } }
                    responses { HttpStatusCode.OK { schema = jsonSchema<List<DocumentSummary>>() } }
                }

                post<LinkLessonDocumentRequest> {
                    documentService.linkLesson(call.getPathUUID(), UUID.fromString(it.documentId), call.requirePrincipal())
                    call.respond(HttpStatusCode.NoContent)
                }.describe {
                    summary = "Link document to lesson"
                    description = "The lesson's teacher links one of their documents. Confirmed participants can then read it."
                    parameters { path("id") { description = "Lesson UUID" } }
                    requestBody { schema = jsonSchema<LinkLessonDocumentRequest>() }
                    responses {
                        HttpStatusCode.NoContent { description = "Linked" }
                        HttpStatusCode.NotFound { description = "LESSON_NOT_FOUND, DOCUMENT_NOT_FOUND"; schema = jsonSchema<ProblemDetail>() }
                    }
                }

                delete("/{documentId}") {
                    documentService.unlinkLesson(call.getPathUUID(), call.getPathUUID("documentId"), call.requirePrincipal())
                    call.respond(HttpStatusCode.NoContent)
                }.describe {
                    summary = "Unlink document from lesson"
                    parameters {
                        path("id") { description = "Lesson UUID" }
                        path("documentId") { description = "Document UUID" }
                    }
                    responses { HttpStatusCode.NoContent { description = "Unlinked" } }
                }
            }
        }
    }
}
