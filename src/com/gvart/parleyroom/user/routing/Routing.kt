package com.gvart.parleyroom.user.routing

import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.user.security.UserPrincipal
import com.gvart.parleyroom.user.service.AuthenticationService
import com.gvart.parleyroom.user.service.UserService
import com.gvart.parleyroom.user.transfer.AuthenticateRequest
import com.gvart.parleyroom.user.transfer.AuthenticateResponse
import com.gvart.parleyroom.user.transfer.RefreshTokenRequest
import com.gvart.parleyroom.user.transfer.UserListResponse
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureRouting() {
    val authenticationService: AuthenticationService by dependencies
    val userService: UserService by dependencies

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
            route("/api/v1/users") {
                get {
                    val result = userService.findAllUsers(call.principal<UserPrincipal>()!!)
                    call.respond(HttpStatusCode.OK, result)
                }.describe {
                    summary = "Get users"
                    description = "Retrieves a list of users. Requires authentication."
                    responses {
                        HttpStatusCode.OK {
                            description = "List of users"
                            schema = jsonSchema<UserListResponse>()
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
