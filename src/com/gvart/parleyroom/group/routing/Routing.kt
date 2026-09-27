package com.gvart.parleyroom.group.routing

import com.gvart.parleyroom.common.routing.getPathUUID
import com.gvart.parleyroom.common.routing.requirePrincipal
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.group.service.GroupService
import com.gvart.parleyroom.group.transfer.GroupMembersRequest
import com.gvart.parleyroom.group.transfer.GroupRequest
import com.gvart.parleyroom.group.transfer.GroupResponse
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

fun Application.configureGroupRouting() {
    val groupService: GroupService by dependencies

    routing {
        authenticate {
            route("/api/v1/groups") {
                get {
                    call.respond(HttpStatusCode.OK, groupService.listGroups(call.requirePrincipal()))
                }.describe {
                    summary = "List groups"
                    description = "The teacher's clubs (speech / reading) with members. Admins see all groups."
                    responses { HttpStatusCode.OK { schema = jsonSchema<List<GroupResponse>>() } }
                }

                post<GroupRequest> {
                    call.respond(HttpStatusCode.Created, groupService.createGroup(it, call.requirePrincipal()))
                }.describe {
                    summary = "Create group"
                    description = "Creates a club owned by the teacher. Optional studentIds must be the teacher's students."
                    requestBody { schema = jsonSchema<GroupRequest>() }
                    responses {
                        HttpStatusCode.Created { schema = jsonSchema<GroupResponse>() }
                        HttpStatusCode.BadRequest { description = "STUDENT_NOT_LINKED"; schema = jsonSchema<ProblemDetail>() }
                    }
                }

                route("/{id}") {
                    get {
                        call.respond(HttpStatusCode.OK, groupService.getGroup(call.getPathUUID(), call.requirePrincipal()))
                    }.describe {
                        summary = "Get group"
                        parameters { path("id") { description = "Group UUID" } }
                        responses {
                            HttpStatusCode.OK { schema = jsonSchema<GroupResponse>() }
                            HttpStatusCode.NotFound { description = "GROUP_NOT_FOUND"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    put<GroupRequest> {
                        call.respond(HttpStatusCode.OK, groupService.updateGroup(call.getPathUUID(), it, call.requirePrincipal()))
                    }.describe {
                        summary = "Replace group"
                        description = "Replaces name, level and type. studentIds is ignored here; use the members endpoints."
                        parameters { path("id") { description = "Group UUID" } }
                        requestBody { schema = jsonSchema<GroupRequest>() }
                        responses { HttpStatusCode.OK { schema = jsonSchema<GroupResponse>() } }
                    }

                    delete {
                        groupService.deleteGroup(call.getPathUUID(), call.requirePrincipal())
                        call.respond(HttpStatusCode.NoContent)
                    }.describe {
                        summary = "Delete group"
                        description = "Deletes the group. Lessons linked to it keep existing with groupId cleared."
                        parameters { path("id") { description = "Group UUID" } }
                        responses { HttpStatusCode.NoContent { description = "Deleted" } }
                    }

                    route("/members") {
                        put<GroupMembersRequest> {
                            val result = groupService.replaceMembers(call.getPathUUID(), it, call.requirePrincipal())
                            call.respond(HttpStatusCode.OK, result)
                        }.describe {
                            summary = "Replace group members"
                            parameters { path("id") { description = "Group UUID" } }
                            requestBody { schema = jsonSchema<GroupMembersRequest>() }
                            responses { HttpStatusCode.OK { schema = jsonSchema<GroupResponse>() } }
                        }

                        post<GroupMembersRequest> {
                            val result = groupService.addMembersTo(call.getPathUUID(), it, call.requirePrincipal())
                            call.respond(HttpStatusCode.OK, result)
                        }.describe {
                            summary = "Add group members"
                            description = "Adds students to the group; students already in it are ignored."
                            parameters { path("id") { description = "Group UUID" } }
                            requestBody { schema = jsonSchema<GroupMembersRequest>() }
                            responses { HttpStatusCode.OK { schema = jsonSchema<GroupResponse>() } }
                        }

                        delete("/{studentId}") {
                            val result = groupService.removeMember(
                                call.getPathUUID(), call.getPathUUID("studentId"), call.requirePrincipal(),
                            )
                            call.respond(HttpStatusCode.OK, result)
                        }.describe {
                            summary = "Remove group member"
                            parameters {
                                path("id") { description = "Group UUID" }
                                path("studentId") { description = "Student UUID" }
                            }
                            responses { HttpStatusCode.OK { schema = jsonSchema<GroupResponse>() } }
                        }
                    }
                }
            }
        }
    }
}
