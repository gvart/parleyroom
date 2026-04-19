package com.gvart.parleyroom.material.routing

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.storage.StorageConfig
import com.gvart.parleyroom.common.storage.StorageService
import com.gvart.parleyroom.common.storage.readBoundedBytes
import com.gvart.parleyroom.common.transfer.PageRequest
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.material.data.MaterialSkill
import com.gvart.parleyroom.material.data.MaterialType
import com.gvart.parleyroom.material.service.MaterialService
import com.gvart.parleyroom.material.service.MaterialShareService
import com.gvart.parleyroom.material.transfer.BulkMaterialRequest
import com.gvart.parleyroom.material.transfer.BulkMaterialResponse
import com.gvart.parleyroom.material.transfer.CreateMaterialInput
import com.gvart.parleyroom.material.transfer.CreateMaterialRequest
import com.gvart.parleyroom.material.transfer.MaterialPageResponse
import com.gvart.parleyroom.material.transfer.MaterialResponse
import com.gvart.parleyroom.material.transfer.ShareListResponse
import com.gvart.parleyroom.material.transfer.ShareRequest
import com.gvart.parleyroom.material.transfer.UpdateMaterialRequest
import com.gvart.parleyroom.user.security.UserPrincipal
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.requestvalidation.ValidationResult
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.UUID

fun Application.configureMaterialRouting() {
    val materialService: MaterialService by dependencies
    val shareService: MaterialShareService by dependencies
    val storageConfig: StorageConfig by dependencies
    val storage: StorageService by dependencies

    routing {
        authenticate {
            route("/api/v1/materials") {
                get {
                    val principal = call.principal<UserPrincipal>()!!
                    val folderId = call.request.queryParameters["folderId"]?.let(UUID::fromString)
                    val unfiled = call.request.queryParameters["unfiled"]?.toBoolean() ?: false
                    val lessonId = call.request.queryParameters["lessonId"]?.let(UUID::fromString)
                    val type = call.request.queryParameters["type"]?.let { MaterialType.valueOf(it) }
                    val level = call.request.queryParameters["level"]?.let { LanguageLevel.valueOf(it) }
                    val skill = call.request.queryParameters["skill"]?.let { MaterialSkill.valueOf(it) }

                    val result = materialService.listMaterials(
                        principal, folderId, unfiled, lessonId, type, level, skill, PageRequest.from(call),
                    )
                    call.respond(HttpStatusCode.OK, result)
                }.describe {
                    summary = "List materials"
                    description = "Paginated list. Teachers see their own library. Students see everything visible through direct material shares, folder shares (cascading), or lesson attachments for confirmed lessons. Admin sees all."
                    parameters {
                        query("folderId") { description = "Filter by folder UUID (contents of the folder, not descendants)"; required = false }
                        query("unfiled") { description = "true to list root-level materials with no folder"; required = false }
                        query("lessonId") { description = "Filter by lesson UUID (attached materials only)"; required = false }
                        query("type") { description = "PDF, AUDIO, VIDEO, LINK"; required = false }
                        query("level") { description = "A1..C2"; required = false }
                        query("skill") { description = "SPEAKING, LISTENING, READING, WRITING, GRAMMAR, VOCAB"; required = false }
                        query("page") { description = "Page number (1-based, default 1)"; required = false }
                        query("pageSize") { description = "Items per page (default 20, max 100)"; required = false }
                    }
                    responses {
                        HttpStatusCode.OK { schema = jsonSchema<MaterialPageResponse>() }
                    }
                }

                post {
                    val principal = call.principal<UserPrincipal>()!!
                    val multipart = call.receiveMultipart()

                    var metadata: CreateMaterialRequest? = null
                    var fileBytes: ByteArray? = null
                    var fileName: String? = null
                    var fileContentType: String? = null

                    while (true) {
                        val part = multipart.readPart() ?: break
                        try {
                            when (part) {
                                is PartData.FormItem -> {
                                    if (part.name == "metadata" && metadata == null) {
                                        metadata = parseMetadata(part.value)
                                    }
                                }
                                is PartData.FileItem -> {
                                    if (part.name == "file" && fileBytes == null) {
                                        fileName = part.originalFileName?.takeIf { it.isNotBlank() }
                                        fileContentType = part.contentType?.toString()
                                        fileBytes = part.provider().toInputStream()
                                            .readBoundedBytes(storageConfig.maxFileSize)
                                    }
                                }
                                else -> Unit
                            }
                        } finally {
                            part.dispose()
                        }
                    }

                    val meta = metadata
                        ?: throw BadRequestException("metadata part is required")

                    val input = when (meta.type) {
                        MaterialType.LINK -> {
                            if (fileBytes != null) {
                                throw BadRequestException("file part must be omitted for LINK materials")
                            }
                            CreateMaterialInput.Link(meta)
                        }
                        else -> {
                            val bytes = fileBytes
                                ?: throw BadRequestException("file part is required for ${meta.type}")
                            val name = fileName
                                ?: throw BadRequestException("file part is missing a filename")
                            val contentType = fileContentType
                                ?: throw BadRequestException("file part is missing Content-Type")
                            CreateMaterialInput.File(
                                request = meta,
                                fileName = name,
                                contentType = contentType,
                                size = bytes.size.toLong(),
                                stream = bytes.inputStream(),
                            )
                        }
                    }

                    val result = materialService.createMaterial(input, principal)
                    call.respond(HttpStatusCode.Created, result)
                }.describe {
                    summary = "Create material"
                    description = "Multipart/form-data. Send a JSON `metadata` part (fields: name, type, folderId?, level?, skill?, url?) and, for non-LINK types, a binary `file` part. Materials are private by default — share them explicitly or attach to a lesson to grant student access."
                    requestBody { schema = jsonSchema<CreateMaterialRequest>() }
                    responses {
                        HttpStatusCode.Created { schema = jsonSchema<MaterialResponse>() }
                        HttpStatusCode.Forbidden { schema = jsonSchema<ProblemDetail>() }
                    }
                }

                post<BulkMaterialRequest>("/bulk") { request ->
                    val principal = call.principal<UserPrincipal>()!!
                    val validation = request.validate()
                    if (validation is ValidationResult.Invalid) {
                        throw BadRequestException(validation.reasons.joinToString())
                    }
                    call.respond(HttpStatusCode.OK, materialService.bulk(request, principal))
                }.describe {
                    summary = "Bulk material operation"
                    description = "Apply MOVE, SHARE or DELETE to many materials in one request."
                    requestBody { schema = jsonSchema<BulkMaterialRequest>() }
                    responses { HttpStatusCode.OK { schema = jsonSchema<BulkMaterialResponse>() } }
                }

                route("/{id}") {
                    get {
                        val principal = call.principal<UserPrincipal>()!!
                        val id = UUID.fromString(call.parameters["id"])
                        call.respond(HttpStatusCode.OK, materialService.getMaterial(id, principal))
                    }.describe {
                        summary = "Get material by ID"
                        parameters { path("id") { description = "UUID of the material" } }
                        responses {
                            HttpStatusCode.OK { schema = jsonSchema<MaterialResponse>() }
                            HttpStatusCode.NotFound { schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    put<UpdateMaterialRequest> {
                        val principal = call.principal<UserPrincipal>()!!
                        val id = UUID.fromString(call.parameters["id"])
                        call.respond(HttpStatusCode.OK, materialService.updateMaterial(id, it, principal))
                    }.describe {
                        summary = "Update material"
                        description = "PATCH-style: only non-null fields are applied. To clear a tag or move to root, use the dedicated DELETE sub-resources."
                        requestBody { schema = jsonSchema<UpdateMaterialRequest>() }
                        parameters { path("id") { description = "UUID of the material" } }
                        responses { HttpStatusCode.OK { schema = jsonSchema<MaterialResponse>() } }
                    }

                    delete {
                        val principal = call.principal<UserPrincipal>()!!
                        val id = UUID.fromString(call.parameters["id"])
                        materialService.deleteMaterial(id, principal)
                        call.respond(HttpStatusCode.NoContent)
                    }.describe {
                        summary = "Delete material"
                        parameters { path("id") { description = "UUID of the material" } }
                        responses { HttpStatusCode.NoContent { description = "Deleted" } }
                    }

                    get("/file") {
                        val principal = call.principal<UserPrincipal>()!!
                        val id = UUID.fromString(call.parameters["id"])

                        val target = materialService.getDownloadTarget(id, principal)
                        val contentType = target.contentType
                            ?.let { runCatching { ContentType.parse(it) }.getOrNull() }
                            ?: ContentType.Application.OctetStream
                        call.response.header(
                            HttpHeaders.ContentDisposition,
                            ContentDisposition.Inline
                                .withParameter(ContentDisposition.Parameters.FileName, target.fileName)
                                .toString(),
                        )
                        call.respondOutputStream(contentType, HttpStatusCode.OK) {
                            storage.stream(target.storageKey).use { it.copyTo(this) }
                        }
                    }.describe {
                        summary = "Download material file"
                        parameters { path("id") { description = "UUID of the material" } }
                        responses {
                            HttpStatusCode.OK { description = "File bytes" }
                            HttpStatusCode.BadRequest { schema = jsonSchema<ProblemDetail>() }
                            HttpStatusCode.NotFound { schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    delete("/folder") {
                        val principal = call.principal<UserPrincipal>()!!
                        val id = UUID.fromString(call.parameters["id"])
                        call.respond(HttpStatusCode.OK, materialService.clearMaterialFolder(id, principal))
                    }.describe {
                        summary = "Remove material from its folder (move to root)"
                        parameters { path("id") { description = "UUID of the material" } }
                        responses { HttpStatusCode.OK { schema = jsonSchema<MaterialResponse>() } }
                    }

                    delete("/level") {
                        val principal = call.principal<UserPrincipal>()!!
                        val id = UUID.fromString(call.parameters["id"])
                        call.respond(HttpStatusCode.OK, materialService.clearMaterialLevel(id, principal))
                    }.describe {
                        summary = "Clear CEFR level tag"
                        parameters { path("id") { description = "UUID of the material" } }
                        responses { HttpStatusCode.OK { schema = jsonSchema<MaterialResponse>() } }
                    }

                    delete("/skill") {
                        val principal = call.principal<UserPrincipal>()!!
                        val id = UUID.fromString(call.parameters["id"])
                        call.respond(HttpStatusCode.OK, materialService.clearMaterialSkill(id, principal))
                    }.describe {
                        summary = "Clear skill tag"
                        parameters { path("id") { description = "UUID of the material" } }
                        responses { HttpStatusCode.OK { schema = jsonSchema<MaterialResponse>() } }
                    }

                    route("/shares") {
                        get {
                            val principal = call.principal<UserPrincipal>()!!
                            val id = UUID.fromString(call.parameters["id"])
                            call.respond(HttpStatusCode.OK, shareService.listMaterialShares(id, principal))
                        }.describe {
                            summary = "List students this material is shared with"
                            parameters { path("id") { description = "UUID of the material" } }
                            responses { HttpStatusCode.OK { schema = jsonSchema<ShareListResponse>() } }
                        }

                        post<ShareRequest> {
                            val principal = call.principal<UserPrincipal>()!!
                            val id = UUID.fromString(call.parameters["id"])
                            call.respond(HttpStatusCode.OK, shareService.shareMaterial(id, it, principal))
                        }.describe {
                            summary = "Share material with students"
                            description = "Adds one grant per listed studentId. Idempotent. Each newly-granted student receives a MATERIAL_SHARED notification."
                            requestBody { schema = jsonSchema<ShareRequest>() }
                            parameters { path("id") { description = "UUID of the material" } }
                            responses { HttpStatusCode.OK { schema = jsonSchema<ShareListResponse>() } }
                        }

                        delete("/{studentId}") {
                            val principal = call.principal<UserPrincipal>()!!
                            val id = UUID.fromString(call.parameters["id"])
                            val studentId = UUID.fromString(call.parameters["studentId"])
                            shareService.revokeMaterial(id, studentId, principal)
                            call.respond(HttpStatusCode.NoContent)
                        }.describe {
                            summary = "Revoke a student's access"
                            parameters {
                                path("id") { description = "UUID of the material" }
                                path("studentId") { description = "UUID of the student" }
                            }
                            responses { HttpStatusCode.NoContent { description = "Revoked" } }
                        }
                    }
                }
            }
        }
    }
}

private fun parseMetadata(value: String): CreateMaterialRequest {
    val request = try {
        Json.decodeFromString<CreateMaterialRequest>(value)
    } catch (e: SerializationException) {
        throw BadRequestException("metadata part is not valid JSON: ${e.message}")
    }
    val validation = request.validate()
    if (validation is ValidationResult.Invalid) {
        throw BadRequestException(validation.reasons.joinToString())
    }
    return request
}

