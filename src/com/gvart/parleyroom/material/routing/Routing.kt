package com.gvart.parleyroom.material.routing

import com.gvart.parleyroom.common.storage.StorageConfig
import com.gvart.parleyroom.common.transfer.PageRequest
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.material.data.MaterialType
import com.gvart.parleyroom.material.service.MaterialService
import com.gvart.parleyroom.material.transfer.CreateMaterialInput
import com.gvart.parleyroom.material.transfer.CreateMaterialRequest
import com.gvart.parleyroom.material.transfer.MaterialPageResponse
import com.gvart.parleyroom.material.transfer.MaterialResponse
import com.gvart.parleyroom.material.transfer.UpdateMaterialRequest
import com.gvart.parleyroom.user.security.UserPrincipal
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
import io.ktor.server.response.respond
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
    val storageConfig: StorageConfig by dependencies

    routing {
        authenticate {
            route("/api/v1/materials") {
                get {
                    val principal = call.principal<UserPrincipal>()!!
                    val studentId = call.request.queryParameters["studentId"]?.let(UUID::fromString)
                    val lessonId = call.request.queryParameters["lessonId"]?.let(UUID::fromString)
                    val type = call.request.queryParameters["type"]?.let { MaterialType.valueOf(it) }

                    val result = materialService.listMaterials(principal, studentId, lessonId, type, PageRequest.from(call))
                    call.respond(HttpStatusCode.OK, result)
                }.describe {
                    summary = "List materials"
                    description = "Lists materials with pagination. Students see materials assigned to them or attached to lessons they attend. Teachers see materials they own. Admins see all."
                    parameters {
                        query("studentId") { description = "Filter by student UUID"; required = false }
                        query("lessonId") { description = "Filter by lesson UUID"; required = false }
                        query("type") { description = "Filter by type (PDF, AUDIO, VIDEO, LINK)"; required = false }
                        query("page") { description = "Page number (1-based, default 1)"; required = false }
                        query("pageSize") { description = "Items per page (default 20, max 100)"; required = false }
                    }
                    responses {
                        HttpStatusCode.OK {
                            description = "Paginated list of materials"
                            schema = jsonSchema<MaterialPageResponse>()
                        }
                    }
                }

                post {
                    val principal = call.principal<UserPrincipal>()!!
                    val multipart = call.receiveMultipart()

                    var metadata: CreateMaterialRequest? = null
                    var fileItem: PartData.FileItem? = null

                    try {
                        while (fileItem == null) {
                            val part = multipart.readPart() ?: break
                            when (part) {
                                is PartData.FormItem -> {
                                    if (part.name == "metadata" && metadata == null) {
                                        metadata = parseMetadata(part.value)
                                    }
                                    part.dispose()
                                }
                                is PartData.FileItem -> {
                                    if (part.name == "file") {
                                        fileItem = part
                                    } else {
                                        part.dispose()
                                    }
                                }
                                else -> part.dispose()
                            }
                        }

                        val meta = metadata
                            ?: throw BadRequestException("metadata part is required and must be sent before the file part")

                        val input = when (meta.type) {
                            MaterialType.LINK -> {
                                if (fileItem != null) {
                                    throw BadRequestException("file part must be omitted for LINK materials")
                                }
                                CreateMaterialInput.Link(meta)
                            }
                            else -> {
                                val fp = fileItem
                                    ?: throw BadRequestException("file part is required for ${meta.type}")
                                val fileName = fp.originalFileName?.takeIf { it.isNotBlank() }
                                    ?: throw BadRequestException("file part is missing a filename")
                                val partContentType = fp.contentType?.toString()
                                    ?: throw BadRequestException("file part is missing Content-Type")
                                val size = fp.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                                    ?: throw BadRequestException("file part is missing Content-Length")
                                if (size > storageConfig.maxFileSize) {
                                    throw BadRequestException("file exceeds max size of ${storageConfig.maxFileSize} bytes")
                                }
                                CreateMaterialInput.File(
                                    request = meta,
                                    fileName = fileName,
                                    contentType = partContentType,
                                    size = size,
                                    stream = fp.provider().toInputStream(),
                                )
                            }
                        }

                        val result = materialService.createMaterial(input, principal)
                        call.respond(HttpStatusCode.Created, result)
                    } finally {
                        fileItem?.dispose()
                    }
                }.describe {
                    summary = "Create material"
                    description = "Creates a material via multipart/form-data. Send a JSON `metadata` part and, for PDF/AUDIO/VIDEO, a binary `file` part. For LINK, omit the file and supply `url` in metadata."
                    requestBody { schema = jsonSchema<CreateMaterialRequest>() }
                    responses {
                        HttpStatusCode.Created {
                            description = "Material created"
                            schema = jsonSchema<MaterialResponse>()
                        }
                        HttpStatusCode.Forbidden {
                            description = "Students cannot create materials"
                            schema = jsonSchema<ProblemDetail>()
                        }
                    }
                }

                route("/{id}") {
                    get {
                        val principal = call.principal<UserPrincipal>()!!
                        val id = UUID.fromString(call.parameters["id"])

                        val result = materialService.getMaterial(id, principal)
                        call.respond(HttpStatusCode.OK, result)
                    }.describe {
                        summary = "Get material by ID"
                        description = "Returns material metadata with a short-lived downloadUrl."
                        parameters { path("id") { description = "UUID of the material" } }
                        responses {
                            HttpStatusCode.OK {
                                description = "Material details"
                                schema = jsonSchema<MaterialResponse>()
                            }
                            HttpStatusCode.NotFound {
                                description = "Material not found"
                                schema = jsonSchema<ProblemDetail>()
                            }
                        }
                    }

                    put<UpdateMaterialRequest> {
                        val principal = call.principal<UserPrincipal>()!!
                        val id = UUID.fromString(call.parameters["id"])

                        val result = materialService.updateMaterial(id, it, principal)
                        call.respond(HttpStatusCode.OK, result)
                    }.describe {
                        summary = "Update material"
                        description = "Renames a material. Only the owning teacher or admin."
                        requestBody { schema = jsonSchema<UpdateMaterialRequest>() }
                        parameters { path("id") { description = "UUID of the material" } }
                        responses {
                            HttpStatusCode.OK {
                                description = "Material updated"
                                schema = jsonSchema<MaterialResponse>()
                            }
                        }
                    }

                    delete {
                        val principal = call.principal<UserPrincipal>()!!
                        val id = UUID.fromString(call.parameters["id"])

                        materialService.deleteMaterial(id, principal)
                        call.respond(HttpStatusCode.NoContent)
                    }.describe {
                        summary = "Delete material"
                        description = "Removes the stored object (if any) and the record. Only the owning teacher or admin."
                        parameters { path("id") { description = "UUID of the material" } }
                        responses {
                            HttpStatusCode.NoContent { description = "Material deleted" }
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
