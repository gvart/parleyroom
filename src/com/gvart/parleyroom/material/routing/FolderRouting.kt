package com.gvart.parleyroom.material.routing

import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.material.service.LessonMaterialService
import com.gvart.parleyroom.material.service.MaterialFolderService
import com.gvart.parleyroom.material.service.MaterialShareService
import com.gvart.parleyroom.material.transfer.AttachMaterialsRequest
import com.gvart.parleyroom.material.transfer.CreateFolderRequest
import com.gvart.parleyroom.material.transfer.FolderTreeNode
import com.gvart.parleyroom.material.transfer.LessonMaterialListResponse
import com.gvart.parleyroom.material.transfer.MaterialFolderResponse
import com.gvart.parleyroom.material.transfer.ShareListResponse
import com.gvart.parleyroom.material.transfer.ShareRequest
import com.gvart.parleyroom.material.transfer.UpdateFolderRequest
import com.gvart.parleyroom.user.security.UserPrincipal
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.requestvalidation.ValidationResult
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.util.UUID

fun Application.configureMaterialFolderRouting() {
    val folderService: MaterialFolderService by dependencies
    val shareService: MaterialShareService by dependencies
    val lessonMaterialService: LessonMaterialService by dependencies

    routing {
        authenticate {
            route("/api/v1/material-folders") {
                get {
                    val principal = call.principal<UserPrincipal>()!!
                    val tree = call.request.queryParameters["tree"]?.toBoolean() ?: false
                    call.respond(HttpStatusCode.OK, folderService.listFolders(principal, tree))
                }.describe {
                    summary = "List material folders"
                    description = "Teachers get their own tree. Students get only folders they can read (shared or descendants of shared). Pass ?tree=true for nested tree, otherwise flat list."
                    parameters {
                        query("tree") { description = "true for nested tree, default flat"; required = false }
                    }
                    responses {
                        HttpStatusCode.OK { description = "Array of FolderTreeNode" }
                    }
                }

                post<CreateFolderRequest> { request ->
                    val principal = call.principal<UserPrincipal>()!!
                    val validation = request.validate()
                    if (validation is ValidationResult.Invalid) {
                        throw BadRequestException(validation.reasons.joinToString())
                    }
                    val result = folderService.createFolder(request, principal)
                    call.respond(HttpStatusCode.Created, result)
                }.describe {
                    summary = "Create material folder"
                    requestBody { schema = jsonSchema<CreateFolderRequest>() }
                    responses {
                        HttpStatusCode.Created { schema = jsonSchema<MaterialFolderResponse>() }
                        HttpStatusCode.Conflict { schema = jsonSchema<ProblemDetail>() }
                    }
                }

                route("/{id}") {
                    get {
                        val principal = call.principal<UserPrincipal>()!!
                        val id = UUID.fromString(call.parameters["id"])
                        call.respond(HttpStatusCode.OK, folderService.getFolder(id, principal))
                    }.describe {
                        summary = "Get folder metadata"
                        parameters { path("id") { description = "UUID of the folder" } }
                        responses {
                            HttpStatusCode.OK { schema = jsonSchema<MaterialFolderResponse>() }
                            HttpStatusCode.NotFound { schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    put<UpdateFolderRequest> { request ->
                        val principal = call.principal<UserPrincipal>()!!
                        val id = UUID.fromString(call.parameters["id"])
                        call.respond(HttpStatusCode.OK, folderService.updateFolder(id, request, principal))
                    }.describe {
                        summary = "Rename / move folder"
                        description = "PATCH-style. Send `name` to rename, `parentFolderId` to move. To move to root, send `moveToRoot: true`."
                        requestBody { schema = jsonSchema<UpdateFolderRequest>() }
                        parameters { path("id") { description = "UUID of the folder" } }
                        responses { HttpStatusCode.OK { schema = jsonSchema<MaterialFolderResponse>() } }
                    }

                    delete {
                        val principal = call.principal<UserPrincipal>()!!
                        val id = UUID.fromString(call.parameters["id"])
                        val cascade = call.request.queryParameters["cascade"]?.toBoolean() ?: false
                        folderService.deleteFolder(id, cascade, principal)
                        call.respond(HttpStatusCode.NoContent)
                    }.describe {
                        summary = "Delete folder"
                        description = "Refuses when folder is non-empty unless cascade=true. Cascade deletes all descendant folders and materials (and their stored files on cascade delete of materials is not yet orphan-cleaned; follow up with a sweep if cascade is common)."
                        parameters {
                            path("id") { description = "UUID of the folder" }
                            query("cascade") { description = "true to delete contents; default false"; required = false }
                        }
                        responses {
                            HttpStatusCode.NoContent { description = "Deleted" }
                            HttpStatusCode.Conflict { schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    route("/shares") {
                        get {
                            val principal = call.principal<UserPrincipal>()!!
                            val id = UUID.fromString(call.parameters["id"])
                            call.respond(HttpStatusCode.OK, shareService.listFolderShares(id, principal))
                        }.describe {
                            summary = "List students this folder is shared with"
                            parameters { path("id") { description = "UUID of the folder" } }
                            responses { HttpStatusCode.OK { schema = jsonSchema<ShareListResponse>() } }
                        }

                        post<ShareRequest> { request ->
                            val principal = call.principal<UserPrincipal>()!!
                            val id = UUID.fromString(call.parameters["id"])
                            call.respond(HttpStatusCode.OK, shareService.shareFolder(id, request, principal))
                        }.describe {
                            summary = "Share folder with students (cascades to all contents & future items)"
                            requestBody { schema = jsonSchema<ShareRequest>() }
                            parameters { path("id") { description = "UUID of the folder" } }
                            responses { HttpStatusCode.OK { schema = jsonSchema<ShareListResponse>() } }
                        }

                        delete("/{studentId}") {
                            val principal = call.principal<UserPrincipal>()!!
                            val id = UUID.fromString(call.parameters["id"])
                            val studentId = UUID.fromString(call.parameters["studentId"])
                            shareService.revokeFolder(id, studentId, principal)
                            call.respond(HttpStatusCode.NoContent)
                        }.describe {
                            summary = "Revoke folder share for a student"
                            parameters {
                                path("id") { description = "UUID of the folder" }
                                path("studentId") { description = "UUID of the student" }
                            }
                            responses { HttpStatusCode.NoContent { description = "Revoked" } }
                        }
                    }
                }
            }

            route("/api/v1/lessons/{lessonId}/materials") {
                get {
                    val principal = call.principal<UserPrincipal>()!!
                    val lessonId = UUID.fromString(call.parameters["lessonId"])
                    call.respond(HttpStatusCode.OK, lessonMaterialService.list(lessonId, principal))
                }.describe {
                    summary = "List materials attached to a lesson"
                    description = "Teachers see everything. Students must be CONFIRMED participants. Visibility persists after lesson completion."
                    parameters { path("lessonId") { description = "UUID of the lesson" } }
                    responses {
                        HttpStatusCode.OK { schema = jsonSchema<LessonMaterialListResponse>() }
                        HttpStatusCode.Forbidden { schema = jsonSchema<ProblemDetail>() }
                    }
                }

                post<AttachMaterialsRequest> { request ->
                    val principal = call.principal<UserPrincipal>()!!
                    val lessonId = UUID.fromString(call.parameters["lessonId"])
                    call.respond(HttpStatusCode.OK, lessonMaterialService.attach(lessonId, request, principal))
                }.describe {
                    summary = "Attach existing materials to a lesson"
                    description = "All listed materials must be owned by the lesson's teacher. Confirmed students receive a MATERIAL_ATTACHED_TO_LESSON notification."
                    requestBody { schema = jsonSchema<AttachMaterialsRequest>() }
                    parameters { path("lessonId") { description = "UUID of the lesson" } }
                    responses { HttpStatusCode.OK { schema = jsonSchema<LessonMaterialListResponse>() } }
                }

                delete("/{materialId}") {
                    val principal = call.principal<UserPrincipal>()!!
                    val lessonId = UUID.fromString(call.parameters["lessonId"])
                    val materialId = UUID.fromString(call.parameters["materialId"])
                    lessonMaterialService.detach(lessonId, materialId, principal)
                    call.respond(HttpStatusCode.NoContent)
                }.describe {
                    summary = "Detach a material from a lesson"
                    parameters {
                        path("lessonId") { description = "UUID of the lesson" }
                        path("materialId") { description = "UUID of the material" }
                    }
                    responses { HttpStatusCode.NoContent { description = "Detached" } }
                }
            }
        }
    }
}
