package com.gvart.parleyroom.admin.routing

import com.gvart.parleyroom.admin.service.AdminService
import com.gvart.parleyroom.admin.transfer.AdminCreateUserRequest
import com.gvart.parleyroom.admin.transfer.AdminSetPasswordRequest
import com.gvart.parleyroom.admin.transfer.AdminSetStatusRequest
import com.gvart.parleyroom.admin.transfer.AdminStatsResponse
import com.gvart.parleyroom.admin.transfer.AdminUpdateUserRequest
import com.gvart.parleyroom.admin.transfer.AdminUserListResponse
import com.gvart.parleyroom.admin.transfer.AdminUserResponse
import com.gvart.parleyroom.common.service.AuthorizationHelper.requireAdmin
import com.gvart.parleyroom.common.transfer.PageRequest
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserStatus
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
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.util.UUID

fun Application.configureAdminRouting() {
    val adminService: AdminService by dependencies

    routing {
        authenticate {
            route("/api/v1/admin") {
                get("/users") {
                    val principal = call.principal<UserPrincipal>()!!
                    requireAdmin(principal)

                    val params = call.request.queryParameters
                    val role = params["role"]?.let { parseEnum<UserRole>(it, "role") }
                    val status = params["status"]?.let { parseEnum<UserStatus>(it, "status") }
                    val search = params["search"]

                    val result = adminService.listUsers(
                        page = PageRequest.from(call),
                        role = role,
                        status = status,
                        search = search,
                    )
                    call.respond(HttpStatusCode.OK, result)
                }.describe {
                    summary = "List users (admin)"
                    description = "Paginated list with optional role/status/search filters. Admin only."
                    parameters {
                        query("page") { description = "Page number (default 1)"; required = false }
                        query("pageSize") { description = "Page size (default 20, max 100)"; required = false }
                        query("role") { description = "Filter: ADMIN|TEACHER|STUDENT"; required = false }
                        query("status") { description = "Filter: ACTIVE|REQUEST|INACTIVE"; required = false }
                        query("search") { description = "Case-insensitive search on email/name/initials"; required = false }
                    }
                    responses {
                        HttpStatusCode.OK { schema = jsonSchema<AdminUserListResponse>() }
                        HttpStatusCode.Forbidden { schema = jsonSchema<ProblemDetail>() }
                        HttpStatusCode.Unauthorized { schema = jsonSchema<ProblemDetail>() }
                    }
                }

                get("/users/{id}") {
                    val principal = call.principal<UserPrincipal>()!!
                    requireAdmin(principal)
                    val id = parseUuid(call.parameters["id"])
                    call.respond(HttpStatusCode.OK, adminService.getUser(id))
                }.describe {
                    summary = "Get user (admin)"
                    description = "Full user detail including email, lockout state, and updated_at. Admin only."
                    parameters { path("id") { description = "User UUID" } }
                    responses {
                        HttpStatusCode.OK { schema = jsonSchema<AdminUserResponse>() }
                        HttpStatusCode.NotFound { schema = jsonSchema<ProblemDetail>() }
                        HttpStatusCode.Forbidden { schema = jsonSchema<ProblemDetail>() }
                    }
                }

                post<AdminCreateUserRequest>("/users") { request ->
                    val principal = call.principal<UserPrincipal>()!!
                    requireAdmin(principal)
                    val result = adminService.createUser(request)
                    call.respond(HttpStatusCode.Created, result)
                }.describe {
                    summary = "Create user (admin)"
                    description = "Create a new user directly with a chosen password. Admin only."
                    requestBody { schema = jsonSchema<AdminCreateUserRequest>() }
                    responses {
                        HttpStatusCode.Created { schema = jsonSchema<AdminUserResponse>() }
                        HttpStatusCode.BadRequest { schema = jsonSchema<ProblemDetail>() }
                        HttpStatusCode.Conflict { schema = jsonSchema<ProblemDetail>() }
                        HttpStatusCode.Forbidden { schema = jsonSchema<ProblemDetail>() }
                    }
                }

                patch<AdminUpdateUserRequest>("/users/{id}") { request ->
                    val principal = call.principal<UserPrincipal>()!!
                    requireAdmin(principal)
                    val id = parseUuid(call.parameters["id"])
                    val result = adminService.updateUser(id, principal, request)
                    call.respond(HttpStatusCode.OK, result)
                }.describe {
                    summary = "Update user (admin)"
                    description = "Partial update. Admins cannot change their own role or deactivate themselves."
                    parameters { path("id") { description = "User UUID" } }
                    requestBody { schema = jsonSchema<AdminUpdateUserRequest>() }
                    responses {
                        HttpStatusCode.OK { schema = jsonSchema<AdminUserResponse>() }
                        HttpStatusCode.BadRequest { schema = jsonSchema<ProblemDetail>() }
                        HttpStatusCode.NotFound { schema = jsonSchema<ProblemDetail>() }
                        HttpStatusCode.Conflict { schema = jsonSchema<ProblemDetail>() }
                        HttpStatusCode.Forbidden { schema = jsonSchema<ProblemDetail>() }
                    }
                }

                delete("/users/{id}") {
                    val principal = call.principal<UserPrincipal>()!!
                    requireAdmin(principal)
                    val id = parseUuid(call.parameters["id"])
                    val hard = call.request.queryParameters["hard"]?.toBoolean() ?: false
                    if (hard) adminService.hardDeleteUser(id, principal)
                    else adminService.softDeleteUser(id, principal)
                    call.respond(HttpStatusCode.NoContent)
                }.describe {
                    summary = "Delete user (admin)"
                    description = "Soft-delete by default (status=INACTIVE). Pass ?hard=true for permanent delete."
                    parameters {
                        path("id") { description = "User UUID" }
                        query("hard") { description = "true for permanent delete"; required = false }
                    }
                    responses {
                        HttpStatusCode.NoContent { description = "User deleted" }
                        HttpStatusCode.BadRequest { schema = jsonSchema<ProblemDetail>() }
                        HttpStatusCode.NotFound { schema = jsonSchema<ProblemDetail>() }
                        HttpStatusCode.Conflict { schema = jsonSchema<ProblemDetail>() }
                        HttpStatusCode.Forbidden { schema = jsonSchema<ProblemDetail>() }
                    }
                }

                post("/users/{id}/unlock") {
                    val principal = call.principal<UserPrincipal>()!!
                    requireAdmin(principal)
                    val id = parseUuid(call.parameters["id"])
                    call.respond(HttpStatusCode.OK, adminService.unlockUser(id))
                }.describe {
                    summary = "Unlock user account (admin)"
                    description = "Clears failedLoginAttempts and lockedUntil so the user can log in again."
                    parameters { path("id") { description = "User UUID" } }
                    responses {
                        HttpStatusCode.OK { schema = jsonSchema<AdminUserResponse>() }
                        HttpStatusCode.NotFound { schema = jsonSchema<ProblemDetail>() }
                        HttpStatusCode.Forbidden { schema = jsonSchema<ProblemDetail>() }
                    }
                }

                post<AdminSetPasswordRequest>("/users/{id}/password") { request ->
                    val principal = call.principal<UserPrincipal>()!!
                    requireAdmin(principal)
                    val id = parseUuid(call.parameters["id"])
                    adminService.setPassword(id, request.newPassword)
                    call.respond(HttpStatusCode.NoContent)
                }.describe {
                    summary = "Set user password (admin)"
                    description = "Directly sets a new password and revokes active refresh tokens."
                    parameters { path("id") { description = "User UUID" } }
                    requestBody { schema = jsonSchema<AdminSetPasswordRequest>() }
                    responses {
                        HttpStatusCode.NoContent { description = "Password updated" }
                        HttpStatusCode.BadRequest { schema = jsonSchema<ProblemDetail>() }
                        HttpStatusCode.NotFound { schema = jsonSchema<ProblemDetail>() }
                        HttpStatusCode.Forbidden { schema = jsonSchema<ProblemDetail>() }
                    }
                }

                post<AdminSetStatusRequest>("/users/{id}/status") { request ->
                    val principal = call.principal<UserPrincipal>()!!
                    requireAdmin(principal)
                    val id = parseUuid(call.parameters["id"])
                    val result = adminService.setStatus(id, principal, request.status)
                    call.respond(HttpStatusCode.OK, result)
                }.describe {
                    summary = "Set user status (admin)"
                    description = "Activate / suspend. Admins cannot deactivate themselves."
                    parameters { path("id") { description = "User UUID" } }
                    requestBody { schema = jsonSchema<AdminSetStatusRequest>() }
                    responses {
                        HttpStatusCode.OK { schema = jsonSchema<AdminUserResponse>() }
                        HttpStatusCode.BadRequest { schema = jsonSchema<ProblemDetail>() }
                        HttpStatusCode.NotFound { schema = jsonSchema<ProblemDetail>() }
                        HttpStatusCode.Forbidden { schema = jsonSchema<ProblemDetail>() }
                    }
                }

                get("/stats") {
                    val principal = call.principal<UserPrincipal>()!!
                    requireAdmin(principal)
                    call.respond(HttpStatusCode.OK, adminService.getStats())
                }.describe {
                    summary = "System statistics (admin)"
                    description = "User, security, activity, and domain counts."
                    responses {
                        HttpStatusCode.OK { schema = jsonSchema<AdminStatsResponse>() }
                        HttpStatusCode.Forbidden { schema = jsonSchema<ProblemDetail>() }
                    }
                }
            }
        }
    }
}

private fun parseUuid(raw: String?): UUID {
    if (raw.isNullOrBlank()) throw BadRequestException("User id is required")
    return try {
        UUID.fromString(raw)
    } catch (_: IllegalArgumentException) {
        throw BadRequestException("Invalid user id")
    }
}

private inline fun <reified T : Enum<T>> parseEnum(value: String, field: String): T =
    try {
        enumValueOf<T>(value.uppercase())
    } catch (_: IllegalArgumentException) {
        throw BadRequestException("Invalid $field: $value")
    }
