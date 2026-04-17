package com.gvart.parleyroom.user.routing

import com.gvart.parleyroom.common.storage.StorageService
import com.gvart.parleyroom.common.storage.readBoundedBytes
import com.gvart.parleyroom.common.transfer.PageRequest
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.user.security.UserPrincipal
import com.gvart.parleyroom.user.service.AuthenticationService
import com.gvart.parleyroom.user.service.UserService
import com.gvart.parleyroom.user.transfer.AuthenticateRequest
import com.gvart.parleyroom.user.transfer.AuthenticateResponse
import com.gvart.parleyroom.user.transfer.LogoutRequest
import com.gvart.parleyroom.user.transfer.RefreshTokenRequest
import com.gvart.parleyroom.user.transfer.UpdateProfileRequest
import com.gvart.parleyroom.user.transfer.UserListResponse
import com.gvart.parleyroom.user.transfer.UserResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.util.UUID

fun Application.configureRouting() {
    val authenticationService: AuthenticationService by dependencies
    val userService: UserService by dependencies
    val storage: StorageService by dependencies

    routing {
        route("/api/v1/token") {
            post<AuthenticateRequest> {
                val result = authenticationService.authenticate(it)

                call.respond(HttpStatusCode.OK, result)
            }.describe {
                summary = "Authenticate user"
                description = "Authenticates a user with email and password. Returns an access token (JWT), a refresh token, and the access token TTL in seconds."
                requestBody {
                    schema = jsonSchema<AuthenticateRequest>()
                }
                responses {
                    HttpStatusCode.OK {
                        description = "Authentication successful"
                        schema = jsonSchema<AuthenticateResponse>()
                    }
                    HttpStatusCode.BadRequest {
                        description = "Invalid request body or validation error"
                        schema = jsonSchema<ProblemDetail>()
                    }
                    HttpStatusCode.Unauthorized {
                        description = "Invalid credentials"
                        schema = jsonSchema<ProblemDetail>()
                    }
                }
            }

            post<RefreshTokenRequest>("/refresh") {
                val result = authenticationService.refresh(it)

                call.respond(HttpStatusCode.OK, result)
            }.describe {
                summary = "Refresh access token"
                description = "Exchanges a refresh token for a new access + refresh token pair. The old refresh token is invalidated (rotation)."
                requestBody {
                    schema = jsonSchema<RefreshTokenRequest>()
                }
                responses {
                    HttpStatusCode.OK {
                        description = "Tokens refreshed"
                        schema = jsonSchema<AuthenticateResponse>()
                    }
                    HttpStatusCode.Unauthorized {
                        description = "Refresh token missing, expired, or revoked"
                        schema = jsonSchema<ProblemDetail>()
                    }
                }
            }
        }

        authenticate {
            route("/api/v1/token") {
                delete {
                    val principal = call.principal<UserPrincipal>()!!
                    val request = call.receive<LogoutRequest>()
                    authenticationService.logout(request.refreshToken, principal.id)
                    call.respond(HttpStatusCode.NoContent)
                }.describe {
                    summary = "Logout / revoke refresh token"
                    description = "Revokes the specified refresh token for the authenticated user."
                    requestBody {
                        schema = jsonSchema<LogoutRequest>()
                    }
                    responses {
                        HttpStatusCode.NoContent {
                            description = "Refresh token revoked successfully"
                        }
                        HttpStatusCode.Unauthorized {
                            description = "Missing or invalid authentication token, or refresh token not found"
                            schema = jsonSchema<ProblemDetail>()
                        }
                    }
                }
            }

            route("/api/v1/users") {
                get {
                    val result = userService.findAllUsers(
                        call.principal<UserPrincipal>()!!,
                        PageRequest.from(call),
                    )
                    call.respond(HttpStatusCode.OK, result)
                }.describe {
                    summary = "Get users"
                    description = "Retrieves a paginated list of users. Requires authentication."
                    parameters {
                        query("page") {
                            description = "Page number (1-based, default 1)"
                            required = false
                        }
                        query("pageSize") {
                            description = "Number of users per page (default 20, max 100)"
                            required = false
                        }
                    }
                    responses {
                        HttpStatusCode.OK {
                            description = "Paginated list of users"
                            schema = jsonSchema<UserListResponse>()
                        }
                        HttpStatusCode.Unauthorized {
                            description = "Missing or invalid authentication token"
                            schema = jsonSchema<ProblemDetail>()
                        }
                    }
                }

                get("/me") {
                    val result = userService.getProfile(call.principal<UserPrincipal>()!!)
                    call.respond(HttpStatusCode.OK, result)
                }.describe {
                    summary = "Get current user profile"
                    description = "Returns the profile of the currently authenticated user."
                    responses {
                        HttpStatusCode.OK {
                            description = "Current user profile"
                            schema = jsonSchema<UserResponse>()
                        }
                        HttpStatusCode.Unauthorized {
                            description = "Missing or invalid authentication token"
                            schema = jsonSchema<ProblemDetail>()
                        }
                    }
                }

                patch<UpdateProfileRequest>("/me") {
                    val result = userService.updateProfile(call.principal<UserPrincipal>()!!, it)
                    call.respond(HttpStatusCode.OK, result)
                }.describe {
                    summary = "Update current user profile"
                    description = "Updates the authenticated user's profile. All fields are optional; at least one must be provided."
                    requestBody {
                        schema = jsonSchema<UpdateProfileRequest>()
                    }
                    responses {
                        HttpStatusCode.OK {
                            description = "Updated user profile"
                            schema = jsonSchema<UserResponse>()
                        }
                        HttpStatusCode.BadRequest {
                            description = "Invalid request body or validation error"
                            schema = jsonSchema<ProblemDetail>()
                        }
                        HttpStatusCode.Unauthorized {
                            description = "Missing or invalid authentication token"
                            schema = jsonSchema<ProblemDetail>()
                        }
                    }
                }

                post("/me/avatar") {
                    val principal = call.principal<UserPrincipal>()!!
                    val multipart = call.receiveMultipart()

                    var fileItem: PartData.FileItem? = null
                    try {
                        while (fileItem == null) {
                            val part = multipart.readPart() ?: break
                            when (part) {
                                is PartData.FileItem -> {
                                    if (part.name == "file") fileItem = part else part.dispose()
                                }
                                else -> part.dispose()
                            }
                        }

                        val fp = fileItem
                            ?: throw BadRequestException("file part is required")
                        val fileName = fp.originalFileName?.takeIf { it.isNotBlank() }
                            ?: throw BadRequestException("file part is missing a filename")
                        val partContentType = fp.contentType?.toString()
                            ?: throw BadRequestException("file part is missing Content-Type")

                        val bytes = fp.provider().toInputStream()
                            .readBoundedBytes(UserService.MAX_AVATAR_SIZE_BYTES)

                        val result = userService.updateAvatar(
                            principal = principal,
                            fileName = fileName,
                            contentType = partContentType,
                            size = bytes.size.toLong(),
                            stream = bytes.inputStream(),
                        )
                        call.respond(HttpStatusCode.OK, result)
                    } finally {
                        fileItem?.dispose()
                    }
                }.describe {
                    summary = "Upload avatar"
                    description = "Uploads or replaces the current user's avatar image. Send as multipart/form-data with a `file` part. Max size 5 MB; allowed content types: image/jpeg, image/png, image/webp, image/gif."
                    responses {
                        HttpStatusCode.OK {
                            description = "Avatar updated"
                            schema = jsonSchema<UserResponse>()
                        }
                        HttpStatusCode.BadRequest {
                            description = "Invalid file, size, or content type"
                            schema = jsonSchema<ProblemDetail>()
                        }
                        HttpStatusCode.Unauthorized {
                            description = "Missing or invalid authentication token"
                            schema = jsonSchema<ProblemDetail>()
                        }
                    }
                }

                delete("/me/avatar") {
                    val result = userService.deleteAvatar(call.principal<UserPrincipal>()!!)
                    call.respond(HttpStatusCode.OK, result)
                }.describe {
                    summary = "Delete avatar"
                    description = "Removes the current user's avatar."
                    responses {
                        HttpStatusCode.OK {
                            description = "Avatar removed"
                            schema = jsonSchema<UserResponse>()
                        }
                        HttpStatusCode.Unauthorized {
                            description = "Missing or invalid authentication token"
                            schema = jsonSchema<ProblemDetail>()
                        }
                    }
                }

                get("/{id}/avatar") {
                    val id = UUID.fromString(call.parameters["id"])
                    val key = userService.getAvatarKey(id)
                    val ext = key.substringAfterLast('.', "").lowercase()
                    val contentType = when (ext) {
                        "jpg", "jpeg" -> ContentType.Image.JPEG
                        "png" -> ContentType.Image.PNG
                        "webp" -> ContentType("image", "webp")
                        "gif" -> ContentType.Image.GIF
                        else -> ContentType.Application.OctetStream
                    }
                    call.respondOutputStream(contentType, HttpStatusCode.OK) {
                        storage.stream(key).use { it.copyTo(this) }
                    }
                }.describe {
                    summary = "Get user avatar"
                    description = "Streams the avatar image for the given user. Requires authentication."
                    parameters { path("id") { description = "UUID of the user" } }
                    responses {
                        HttpStatusCode.OK { description = "Avatar image bytes" }
                        HttpStatusCode.NotFound {
                            description = "User not found or no avatar set"
                            schema = jsonSchema<ProblemDetail>()
                        }
                        HttpStatusCode.Unauthorized {
                            description = "Missing or invalid authentication token"
                            schema = jsonSchema<ProblemDetail>()
                        }
                    }
                }
            }
        }
    }
}
