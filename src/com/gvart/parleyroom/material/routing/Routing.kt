package com.gvart.parleyroom.material.routing

import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.material.data.MaterialType
import com.gvart.parleyroom.material.service.MaterialService
import com.gvart.parleyroom.material.transfer.CreateMaterialRequest
import com.gvart.parleyroom.material.transfer.CreateMaterialResponse
import com.gvart.parleyroom.material.transfer.MaterialResponse
import com.gvart.parleyroom.material.transfer.UpdateMaterialRequest
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

fun Application.configureMaterialRouting() {
    val materialService: MaterialService by dependencies

    routing {
        authenticate {
            route("/api/v1/materials") {
                get {
                    val principal = call.principal<UserPrincipal>()!!
                    val studentId = call.queryParameters["studentId"]?.let(UUID::fromString)
                    val lessonId = call.queryParameters["lessonId"]?.let(UUID::fromString)
                    val type = call.queryParameters["type"]?.let { MaterialType.valueOf(it) }

                    val result = materialService.listMaterials(principal, studentId, lessonId, type)
                    call.respond(HttpStatusCode.OK, result)
                }.describe {
                    summary = "List materials"
                    description = "Lists materials. Students see materials assigned to them or attached to lessons they attend. Teachers see materials they own. Admins see all."
                    parameters {
                        query("studentId") { description = "Filter by student UUID"; required = false }
                        query("lessonId") { description = "Filter by lesson UUID"; required = false }
                        query("type") { description = "Filter by type (PDF, AUDIO, VIDEO, LINK)"; required = false }
                    }
                    responses {
                        HttpStatusCode.OK {
                            description = "List of materials"
                            schema = jsonSchema<List<MaterialResponse>>()
                        }
                    }
                }

                post<CreateMaterialRequest> {
                    val principal = call.principal<UserPrincipal>()!!

                    val result = materialService.createMaterial(it, principal)
                    call.respond(HttpStatusCode.Created, result)
                }.describe {
                    summary = "Create material"
                    description = "Creates a material. For PDF/AUDIO/VIDEO, returns an uploadUrl the client must PUT the file to. For LINK, supply the external url."
                    requestBody { schema = jsonSchema<CreateMaterialRequest>() }
                    responses {
                        HttpStatusCode.Created {
                            description = "Material created"
                            schema = jsonSchema<CreateMaterialResponse>()
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
