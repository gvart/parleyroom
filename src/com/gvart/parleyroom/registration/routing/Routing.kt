package com.gvart.parleyroom.registration.routing

import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.registration.initializer.initializeAdminUser
import com.gvart.parleyroom.registration.service.PasswordResetService
import com.gvart.parleyroom.registration.service.RegistrationService
import com.gvart.parleyroom.registration.transfer.InviteUserRequest
import com.gvart.parleyroom.registration.transfer.InviteUserResponse
import com.gvart.parleyroom.registration.transfer.RegisterUserRequest
import com.gvart.parleyroom.registration.transfer.ResetPasswordRequest
import com.gvart.parleyroom.registration.transfer.ResetPasswordResponse
import com.gvart.parleyroom.user.security.UserPrincipal
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.util.UUID

fun Application.configureRegistrationModule() {
    val registrationService: RegistrationService by dependencies
    val passwordResetService: PasswordResetService by dependencies

    dependencies {
        provide(RegistrationService::class)
        provide(PasswordResetService::class)
    }

    initializeAdminUser()

    routing {
        route("/api/v1/registration") {
            post<RegisterUserRequest> {

                registrationService.registerUser(it)
                call.respond(HttpStatusCode.Created)
            }.describe {
                summary = "Register user"
                description = "Registers a new user using an invitation token."
                requestBody {
                    schema = jsonSchema<RegisterUserRequest>()
                }
                responses {
                    HttpStatusCode.Created {
                        description = "User registered successfully"
                    }
                    HttpStatusCode.BadRequest {
                        description = "Invalid request body or validation error"
                        schema = jsonSchema<ProblemDetail>()
                    }
                    HttpStatusCode.Conflict {
                        description = "User with this email already exists"
                        schema = jsonSchema<ProblemDetail>()
                    }
                }
            }

            authenticate {
                post<InviteUserRequest>("/invite") {
                    val principal = call.principal<UserPrincipal>()!!

                    val result = registrationService.inviteUser(it, principal)
                    call.respond(HttpStatusCode.Created, result)
                }.describe {
                    summary = "Invite user"
                    description = "Sends an invitation to a new user with a specified role. Requires authentication."
                    requestBody {
                        schema = jsonSchema<InviteUserRequest>()
                    }
                    responses {
                        HttpStatusCode.Created {
                            description = "Invitation sent successfully"
                            schema = jsonSchema<InviteUserResponse>()
                        }
                        HttpStatusCode.BadRequest {
                            description = "Invalid request body or validation error"
                            schema = jsonSchema<ProblemDetail>()
                        }
                        HttpStatusCode.Unauthorized {
                            description = "Missing or invalid authentication token"
                            schema = jsonSchema<ProblemDetail>()
                        }
                        HttpStatusCode.Forbidden {
                            description = "Insufficient permissions to invite users"
                            schema = jsonSchema<ProblemDetail>()
                        }
                    }
                }
            }
        }

        route("/api/v1/password-reset") {
            authenticate {
                post {
                    val principal = call.principal<UserPrincipal>()!!

                    val result = passwordResetService.requestResetForSelf(principal)
                    call.respond(HttpStatusCode.Created, result)
                }.describe {
                    summary = "Request password reset for self"
                    description = "Generates a password reset token for the authenticated user."
                    responses {
                        HttpStatusCode.Created {
                            description = "Password reset token generated"
                            schema = jsonSchema<ResetPasswordResponse>()
                        }
                        HttpStatusCode.Unauthorized {
                            description = "Missing or invalid authentication token"
                            schema = jsonSchema<ProblemDetail>()
                        }
                    }
                }

                post("/{userId}") {
                    val principal = call.principal<UserPrincipal>()!!
                    val userId = UUID.fromString(call.parameters["userId"])

                    val result = passwordResetService.requestResetForUser(userId, principal)
                    call.respond(HttpStatusCode.Created, result)
                }.describe {
                    summary = "Request password reset for another user"
                    description = "Generates a password reset token for the specified user. Requires admin privileges."
                    parameters {
                        path("userId") {
                            description = "UUID of the user to reset password for"
                        }
                    }
                    responses {
                        HttpStatusCode.Created {
                            description = "Password reset token generated"
                            schema = jsonSchema<ResetPasswordResponse>()
                        }
                        HttpStatusCode.Unauthorized {
                            description = "Missing or invalid authentication token"
                            schema = jsonSchema<ProblemDetail>()
                        }
                        HttpStatusCode.Forbidden {
                            description = "Insufficient permissions"
                            schema = jsonSchema<ProblemDetail>()
                        }
                        HttpStatusCode.NotFound {
                            description = "User not found"
                            schema = jsonSchema<ProblemDetail>()
                        }
                    }
                }
            }

            post<ResetPasswordRequest>("/confirm") {

                passwordResetService.resetPassword(it.token, it.newPassword)
                call.respond(HttpStatusCode.OK)
            }.describe {
                summary = "Confirm password reset"
                description = "Resets the password using a valid reset token."
                requestBody {
                    schema = jsonSchema<ResetPasswordRequest>()
                }
                responses {
                    HttpStatusCode.OK {
                        description = "Password reset successfully"
                    }
                    HttpStatusCode.BadRequest {
                        description = "Invalid or expired token"
                        schema = jsonSchema<ProblemDetail>()
                    }
                }
            }
        }
    }
}