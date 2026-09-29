package com.gvart.parleyroom.homework.routing

import com.gvart.parleyroom.common.routing.getPathUUID
import com.gvart.parleyroom.common.routing.getQueryUUID
import com.gvart.parleyroom.common.routing.requirePrincipal
import com.gvart.parleyroom.common.storage.StorageConfig
import com.gvart.parleyroom.common.storage.StorageService
import com.gvart.parleyroom.common.storage.readBoundedBytes
import com.gvart.parleyroom.common.transfer.PageRequest
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.homework.data.HomeworkStatus
import com.gvart.parleyroom.homework.service.AssignmentService
import com.gvart.parleyroom.homework.service.HomeworkService
import com.gvart.parleyroom.homework.service.HomeworkUploadService
import com.gvart.parleyroom.homework.transfer.AnswersSavedResponse
import com.gvart.parleyroom.homework.transfer.AssignmentItemInput
import com.gvart.parleyroom.homework.transfer.AssignmentPageResponse
import com.gvart.parleyroom.homework.transfer.AssignmentResponse
import com.gvart.parleyroom.homework.transfer.CreateAssignmentRequest
import com.gvart.parleyroom.homework.transfer.HomeworkCountsResponse
import com.gvart.parleyroom.homework.transfer.HomeworkPageResponse
import com.gvart.parleyroom.homework.transfer.HomeworkResponse
import com.gvart.parleyroom.homework.transfer.HomeworkUploadResponse
import com.gvart.parleyroom.homework.transfer.ReviewDraftRequest
import com.gvart.parleyroom.homework.transfer.ReviewRequest
import com.gvart.parleyroom.homework.transfer.SaveAnswersRequest
import com.gvart.parleyroom.homework.transfer.UpdateAssignmentItemRequest
import com.gvart.parleyroom.homework.transfer.UpdateAssignmentRequest
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.datetime.LocalDate

fun Application.configureHomeworkRouting() {
    val assignmentService: AssignmentService by dependencies
    val homeworkService: HomeworkService by dependencies
    val uploadService: HomeworkUploadService by dependencies
    val storage: StorageService by dependencies
    val storageConfig: StorageConfig by dependencies

    routing {
        authenticate {
            route("/api/v1/assignments") {
                post<CreateAssignmentRequest> {
                    call.respond(HttpStatusCode.Created, assignmentService.create(it, call.requirePrincipal()))
                }.describe {
                    summary = "Assign homework"
                    description = "Teacher only. Recipients = studentIds + members of groupIds (expanded now). DOCUMENT items snapshot the document."
                    requestBody { schema = jsonSchema<CreateAssignmentRequest>() }
                    responses {
                        HttpStatusCode.Created { schema = jsonSchema<AssignmentResponse>() }
                        HttpStatusCode.BadRequest {
                            description = "ASSIGNMENT_NO_STUDENTS, HOMEWORK_ITEM_INVALID (with pointer), STUDENT_NOT_LINKED, VALIDATION_FAILED"
                            schema = jsonSchema<ProblemDetail>()
                        }
                        HttpStatusCode.NotFound {
                            description = "GROUP_NOT_FOUND, LESSON_NOT_FOUND, DOCUMENT_NOT_FOUND, MATERIAL_NOT_FOUND"
                            schema = jsonSchema<ProblemDetail>()
                        }
                    }
                }

                get {
                    val result = assignmentService.list(
                        call.requirePrincipal(),
                        call.getQueryUUID("studentId"),
                        call.getQueryUUID("groupId"),
                        call.getQueryUUID("lessonId"),
                        PageRequest.from(call),
                    )
                    call.respond(HttpStatusCode.OK, result)
                }.describe {
                    summary = "List assignments"
                    description = "Teachers: their assignments (newest first) with per-status counts. Admins: all."
                    responses { HttpStatusCode.OK { schema = jsonSchema<AssignmentPageResponse>() } }
                }

                route("/{id}") {
                    get {
                        call.respond(HttpStatusCode.OK, assignmentService.get(call.getPathUUID(), call.requirePrincipal()))
                    }.describe {
                        summary = "Get an assignment"
                        description = "Items with full snapshots (incl. solutions) and every student's homework summary."
                        responses {
                            HttpStatusCode.OK { schema = jsonSchema<AssignmentResponse>() }
                            HttpStatusCode.NotFound { description = "ASSIGNMENT_NOT_FOUND"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    patch<UpdateAssignmentRequest> {
                        call.respond(HttpStatusCode.OK, assignmentService.update(call.getPathUUID(), it, call.requirePrincipal()))
                    }.describe {
                        summary = "Update an assignment"
                        description = "Title, instructions, due date. Items change through /items while every homework is OPEN."
                        requestBody { schema = jsonSchema<UpdateAssignmentRequest>() }
                        responses { HttpStatusCode.OK { schema = jsonSchema<AssignmentResponse>() } }
                    }

                    post<AssignmentItemInput>("/items") {
                        call.respond(HttpStatusCode.OK, assignmentService.addItem(call.getPathUUID(), it, call.requirePrincipal()))
                    }.describe {
                        summary = "Add an item"
                        description = "The owning teacher, while every homework of the assignment is OPEN. Appended last."
                        requestBody { schema = jsonSchema<AssignmentItemInput>() }
                        responses {
                            HttpStatusCode.OK { schema = jsonSchema<AssignmentResponse>() }
                            HttpStatusCode.BadRequest { description = "HOMEWORK_ITEM_INVALID (with pointer), VALIDATION_FAILED"; schema = jsonSchema<ProblemDetail>() }
                            HttpStatusCode.Conflict { description = "ASSIGNMENT_ITEMS_LOCKED"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    patch<UpdateAssignmentItemRequest>("/items/{itemId}") {
                        call.respond(HttpStatusCode.OK, assignmentService.updateItem(call.getPathUUID(), call.getPathUUID("itemId"), it, call.requirePrincipal()))
                    }.describe {
                        summary = "Edit an item"
                        description = "Title, task, response type (drops that item's answers and uploads) or a DOCUMENT item's blocks " +
                            "(answers that no longer fit a unit are dropped). While every homework is OPEN."
                        requestBody { schema = jsonSchema<UpdateAssignmentItemRequest>() }
                        responses {
                            HttpStatusCode.OK { schema = jsonSchema<AssignmentResponse>() }
                            HttpStatusCode.BadRequest { description = "HOMEWORK_ITEM_INVALID, DOCUMENT_INVALID_BLOCK (with pointer)"; schema = jsonSchema<ProblemDetail>() }
                            HttpStatusCode.NotFound { description = "HOMEWORK_ITEM_NOT_FOUND"; schema = jsonSchema<ProblemDetail>() }
                            HttpStatusCode.Conflict { description = "ASSIGNMENT_ITEMS_LOCKED"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    delete("/items/{itemId}") {
                        call.respond(HttpStatusCode.OK, assignmentService.deleteItem(call.getPathUUID(), call.getPathUUID("itemId"), call.requirePrincipal()))
                    }.describe {
                        summary = "Remove an item"
                        description = "Deletes its answers and uploads. While every homework is OPEN; the last item cannot be removed."
                        responses {
                            HttpStatusCode.OK { schema = jsonSchema<AssignmentResponse>() }
                            HttpStatusCode.Conflict { description = "ASSIGNMENT_ITEMS_LOCKED, ASSIGNMENT_LAST_ITEM"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    delete {
                        assignmentService.delete(call.getPathUUID(), call.requirePrincipal())
                        call.respond(HttpStatusCode.NoContent)
                    }.describe {
                        summary = "Delete an assignment"
                        description = "Deletes every student's homework and stored uploads. Teacher or admin."
                        responses { HttpStatusCode.NoContent { description = "Deleted" } }
                    }
                }
            }

            route("/api/v1/homework") {
                get {
                    val params = call.request.queryParameters
                    val filter = HomeworkService.ListFilter(
                        studentId = call.getQueryUUID("studentId"),
                        assignmentId = call.getQueryUUID("assignmentId"),
                        documentId = call.getQueryUUID("documentId"),
                        lessonId = call.getQueryUUID("lessonId"),
                        statuses = params["status"]?.split(',')?.filter(String::isNotBlank)?.map { value ->
                            enumOrBad<HomeworkStatus>(value.trim(), "status")
                        }.orEmpty(),
                        dueBefore = params["dueBefore"]?.let { dateOrBad(it, "dueBefore") },
                        dueAfter = params["dueAfter"]?.let { dateOrBad(it, "dueAfter") },
                        sort = params["sort"]?.let { enumOrBad<HomeworkService.Sort>(it.uppercase(), "sort") },
                    )
                    call.respond(HttpStatusCode.OK, homeworkService.list(call.requirePrincipal(), filter, PageRequest.from(call)))
                }.describe {
                    summary = "List homework"
                    description = "Students: their own. Teachers: homework of their assignments. status is a comma list; sort = due | submitted | created."
                    parameters {
                        query("studentId") { required = false }
                        query("assignmentId") { required = false }
                        query("documentId") { description = "Homework with a DOCUMENT item made from this document"; required = false }
                        query("lessonId") { required = false }
                        query("status") { description = "Comma list of OPEN, SUBMITTED, REVIEWED, DONE"; required = false }
                        query("dueBefore") { description = "YYYY-MM-DD, inclusive"; required = false }
                        query("dueAfter") { description = "YYYY-MM-DD, inclusive"; required = false }
                        query("sort") { description = "due (student default) | submitted | created (teacher default)"; required = false }
                        query("page") { required = false }
                        query("pageSize") { required = false }
                    }
                    responses { HttpStatusCode.OK { schema = jsonSchema<HomeworkPageResponse>() } }
                }

                get("/counts") {
                    call.respond(HttpStatusCode.OK, homeworkService.counts(call.requirePrincipal()))
                }.describe {
                    summary = "Homework dashboard counts"
                    description = "Teacher/admin: toReview, overdue, openTotal. Student: open, dueSoon, returned."
                    responses { HttpStatusCode.OK { schema = jsonSchema<HomeworkCountsResponse>() } }
                }

                route("/{id}") {
                    get {
                        call.respond(HttpStatusCode.OK, homeworkService.get(call.getPathUUID(), call.requirePrincipal()))
                    }.describe {
                        summary = "Get homework"
                        description = "Solutions and results are hidden from the student until REVIEWED/DONE; the teacher also sees in-progress (OPEN) answers."
                        responses {
                            HttpStatusCode.OK { schema = jsonSchema<HomeworkResponse>() }
                            HttpStatusCode.NotFound { description = "HOMEWORK_NOT_FOUND"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    delete {
                        homeworkService.delete(call.getPathUUID(), call.requirePrincipal())
                        call.respond(HttpStatusCode.NoContent)
                    }.describe {
                        summary = "Remove one student's homework"
                        responses { HttpStatusCode.NoContent { description = "Deleted" } }
                    }

                    put<SaveAnswersRequest>("/answers") {
                        call.respond(HttpStatusCode.OK, homeworkService.saveAnswers(call.getPathUUID(), it, call.requirePrincipal()))
                    }.describe {
                        summary = "Save draft answers"
                        description = "Partial, idempotent merge of the listed units; answer null removes one. OPEN only."
                        requestBody { schema = jsonSchema<SaveAnswersRequest>() }
                        responses {
                            HttpStatusCode.OK { schema = jsonSchema<AnswersSavedResponse>() }
                            HttpStatusCode.BadRequest { description = "HOMEWORK_ITEM_INVALID, HOMEWORK_ANSWER_INVALID (with pointer)"; schema = jsonSchema<ProblemDetail>() }
                            HttpStatusCode.Conflict { description = "SUBMISSION_LOCKED"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    post("/submit") {
                        call.respond(HttpStatusCode.OK, homeworkService.submit(call.getPathUUID(), call.requirePrincipal()))
                    }.describe {
                        summary = "Submit homework"
                        description = "Runs the auto-check, locks the answers and notifies the teacher."
                        responses {
                            HttpStatusCode.OK { schema = jsonSchema<HomeworkResponse>() }
                            HttpStatusCode.Conflict { description = "SUBMISSION_LOCKED"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    put<ReviewDraftRequest>("/review") {
                        call.respond(HttpStatusCode.OK, homeworkService.saveReview(call.getPathUUID(), it.feedback, it.units, call.requirePrincipal()))
                    }.describe {
                        summary = "Save a review draft"
                        description = "Teacher autosave on OPEN or SUBMITTED homework; no status change, hidden from the student until sent."
                        requestBody { schema = jsonSchema<ReviewDraftRequest>() }
                        responses {
                            HttpStatusCode.OK { schema = jsonSchema<HomeworkResponse>() }
                            HttpStatusCode.Conflict { description = "HOMEWORK_INVALID_STATE"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    post<ReviewRequest>("/review") {
                        call.respond(HttpStatusCode.OK, homeworkService.review(call.getPathUUID(), it.feedback, it.units, it.outcome, call.requirePrincipal()))
                    }.describe {
                        summary = "Review homework"
                        description = "outcome REVIEWED, RETURNED (back to OPEN for rework) or DONE. Any status but DONE; from OPEN, REVIEWED/DONE auto-check the current answers."
                        requestBody { schema = jsonSchema<ReviewRequest>() }
                        responses {
                            HttpStatusCode.OK { schema = jsonSchema<HomeworkResponse>() }
                            HttpStatusCode.Conflict { description = "HOMEWORK_INVALID_STATE"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    post("/items/{itemId}/uploads") {
                        val principal = call.requirePrincipal()
                        val homeworkId = call.getPathUUID()
                        val itemId = call.getPathUUID("itemId")
                        uploadService.requireUploadable(homeworkId, itemId, principal)

                        var fileName: String? = null
                        var contentType: String? = null
                        var bytes: ByteArray? = null
                        val multipart = call.receiveMultipart()
                        while (true) {
                            val part = multipart.readPart() ?: break
                            try {
                                if (part is PartData.FileItem && part.name == "file" && bytes == null) {
                                    fileName = part.originalFileName?.takeIf { it.isNotBlank() } ?: "file"
                                    contentType = part.contentType?.toString()
                                    bytes = part.provider().toInputStream().readBoundedBytes(storageConfig.maxFileSize)
                                }
                            } finally {
                                part.release()
                            }
                        }
                        val data = bytes ?: throw BadRequestException("file part is required")
                        val type = contentType ?: throw BadRequestException("file part is missing Content-Type", code = "UPLOAD_TYPE_NOT_ALLOWED")
                        call.respond(HttpStatusCode.Created, uploadService.upload(homeworkId, itemId, fileName!!, type, data, principal))
                    }.describe {
                        summary = "Upload an audio / video / file answer"
                        description = "multipart/form-data with a `file` part. AUDIO/VIDEO/FILE units only, while OPEN, at most 5 per unit. List the returned id in the unit's answer (uploadIds)."
                        responses {
                            HttpStatusCode.Created { schema = jsonSchema<HomeworkUploadResponse>() }
                            HttpStatusCode.BadRequest { description = "UPLOAD_TYPE_NOT_ALLOWED, FILE_TOO_LARGE, HOMEWORK_ITEM_INVALID"; schema = jsonSchema<ProblemDetail>() }
                            HttpStatusCode.Conflict { description = "SUBMISSION_LOCKED, UPLOAD_LIMIT_REACHED"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    delete("/items/{itemId}/uploads/{uploadId}") {
                        uploadService.delete(call.getPathUUID(), call.getPathUUID("itemId"), call.getPathUUID("uploadId"), call.requirePrincipal())
                        call.respond(HttpStatusCode.NoContent)
                    }.describe {
                        summary = "Delete an upload"
                        description = "Student, while OPEN. Also removes it from the unit's answer."
                        responses { HttpStatusCode.NoContent { description = "Deleted" } }
                    }

                    get("/uploads/{uploadId}/file") {
                        val target = uploadService.downloadTarget(call.getPathUUID(), call.getPathUUID("uploadId"), call.requirePrincipal())
                        val contentType = runCatching { ContentType.parse(target.contentType) }.getOrDefault(ContentType.Application.OctetStream)
                        call.response.header(
                            HttpHeaders.ContentDisposition,
                            ContentDisposition.Inline.withParameter(ContentDisposition.Parameters.FileName, target.fileName).toString(),
                        )
                        call.respondOutputStream(contentType, HttpStatusCode.OK) {
                            storage.stream(target.storageKey).use { it.copyTo(this) }
                        }
                    }.describe {
                        summary = "Download an upload"
                        description = "The student, the teacher or an admin."
                        responses {
                            HttpStatusCode.OK { description = "File bytes" }
                            HttpStatusCode.NotFound { description = "HOMEWORK_NOT_FOUND, HOMEWORK_UPLOAD_NOT_FOUND"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }
                }
            }
        }
    }
}

private inline fun <reified T : Enum<T>> enumOrBad(value: String, name: String): T =
    enumValues<T>().firstOrNull { it.name == value }
        ?: throw BadRequestException("Unknown $name '$value'", code = "VALIDATION_FAILED")

private fun dateOrBad(value: String, name: String): LocalDate =
    runCatching { LocalDate.parse(value) }.getOrElse { throw BadRequestException("$name must be YYYY-MM-DD", code = "VALIDATION_FAILED") }
