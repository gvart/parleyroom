package com.gvart.parleyroom.registration.routing


import com.gvart.parleyroom.common.routing.idempotencyKeyHeader
import com.gvart.parleyroom.common.routing.respondIdempotent
import com.gvart.parleyroom.common.service.IdempotencyService
import com.gvart.parleyroom.ai.service.OnboardingService
import com.gvart.parleyroom.common.routing.getPathUUID
import com.gvart.parleyroom.common.routing.requirePrincipal
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.registration.initializer.initializeAdminUser
import com.gvart.parleyroom.registration.service.PasswordResetService
import com.gvart.parleyroom.registration.service.RegistrationService
import com.gvart.parleyroom.registration.transfer.InviteListResponse
import com.gvart.parleyroom.registration.transfer.InviteStatus
import com.gvart.parleyroom.registration.transfer.InviteUserRequest
import com.gvart.parleyroom.registration.transfer.InviteUserResponse
import com.gvart.parleyroom.registration.transfer.RegisterUserRequest
import com.gvart.parleyroom.registration.transfer.ResetPasswordRequest
import com.gvart.parleyroom.registration.transfer.ResetPasswordResponse
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.util.UUID

fun Application.configureRegistrationModule() {
    val registrationService: RegistrationService by dependencies
    val passwordResetService: PasswordResetService by dependencies
    val idempotency: IdempotencyService by dependencies

    dependencies {
        provide(RegistrationService::class)
        provide(PasswordResetService::class)
    }

    initializeAdminUser()

    routing {
        route("/api/v1/registration") {
            post<RegisterUserRequest> {

                val registered = registrationService.registerUser(it)
                registered.teacherId?.let { teacherId ->
                    // Resolved per request: the AI module is configured after the registration module. Never fails the signup.
                    runCatching {
                        val onboarding: OnboardingService = call.application.dependencies.resolve()
                        onboarding.startInBackground(registered.userId, teacherId)
                    }.onFailure { e -> call.application.log.warn("Onboarding draft not started for user ${registered.userId}", e) }
                }
                call.respond(HttpStatusCode.Created)
            }.describe {
                summary = "Register user"
                description = "Registers a new user using an invitation token. For a student invited by a teacher, " +
                    "the invite's level is copied to the student and its attached materials (those still existing) " +
                    "are shared with the student, without notifications. If AI is available, an onboarding words draft is then started " +
                    "in the background from the readable materials (see GET /api/v1/students/{studentId}/onboarding)."
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
                    val principal = call.requirePrincipal()

                    call.respondIdempotent(idempotency, it, HttpStatusCode.Created) { registrationService.inviteUser(it, principal) }
                }.describe {
                    idempotencyKeyHeader()
                    summary = "Invite user"
                    description = "Creates an invitation for a new user with a specified role and emails the registration link " +
                        "(${'$'}{APP_BASE_URL}/register?token=…&email=…). Requires authentication. " +
                        "The link expires after the admin setting inviteExpiryDays. The email goes out after the invite is saved; " +
                        "if sending fails the invite still succeeds with emailSent = false and the token can be shared manually. " +
                        "Students get the email in the invite's nativeLanguage, teachers and admins in the inviter's UI locale. " +
                        "Student invites take an optional nativeLanguage (ru | uk | en, default ru; 400 UNSUPPORTED_NATIVE_LANGUAGE otherwise) " +
                        "and an optional level. Teachers inviting a student may attach up to 5 of their own materials via materialIds " +
                        "(400 INVITE_MATERIALS_NOT_ALLOWED for other inviters, 400 INVITE_MATERIAL_LINK_NOT_ALLOWED for links); " +
                        "they are filed into the teacher's \"Onboarding – <email>\" folder and shared with the student on signup."
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
                        HttpStatusCode.NotFound {
                            description = "An attached material does not exist or is not owned by the inviter (MATERIAL_NOT_FOUND)"
                            schema = jsonSchema<ProblemDetail>()
                        }
                        HttpStatusCode.Conflict {
                            description = "User or pending invitation already exists for this email"
                            schema = jsonSchema<ProblemDetail>()
                        }
                    }
                }

                get("/invites") {
                    val principal = call.requirePrincipal()
                    val status = call.request.queryParameters["status"]?.let { raw ->
                        InviteStatus.entries.firstOrNull { it.name == raw.trim() } ?: throw BadRequestException("Unknown invite status: $raw")
                    }

                    call.respond(HttpStatusCode.OK, registrationService.listInvites(principal, status))
                }.describe {
                    summary = "List invites"
                    description = "Invites, newest first. Admins see all, teachers the ones they sent; students 403. " +
                        "status is derived: used → USED; else expiresAt in the past → EXPIRED; else PENDING. " +
                        "lastSentAt is when the invite email last went out successfully (null if never)."
                    parameters {
                        query("status") { description = "PENDING, EXPIRED or USED"; required = false }
                    }
                    responses {
                        HttpStatusCode.OK {
                            description = "Invites"
                            schema = jsonSchema<InviteListResponse>()
                        }
                        HttpStatusCode.BadRequest {
                            description = "Unknown status"
                            schema = jsonSchema<ProblemDetail>()
                        }
                        HttpStatusCode.Unauthorized {
                            description = "Missing or invalid authentication token"
                            schema = jsonSchema<ProblemDetail>()
                        }
                        HttpStatusCode.Forbidden {
                            description = "Students cannot list invites"
                            schema = jsonSchema<ProblemDetail>()
                        }
                    }
                }

                post("/invites/{id}/resend") {
                    val principal = call.requirePrincipal()

                    val result = registrationService.resendInvite(call.getPathUUID("id"), principal)
                    call.respond(HttpStatusCode.OK, result)
                }.describe {
                    summary = "Resend invite"
                    description = "Rotates the invite's token (the previous link stops working), resets expiresAt to now + inviteExpiryDays " +
                        "and emails the new link again. Works for expired invites. Admins may resend any invite, teachers only their own. " +
                        "As on invite, a failed send still returns 200 with emailSent = false."
                    parameters {
                        path("id") { description = "UUID of the invite" }
                    }
                    responses {
                        HttpStatusCode.OK {
                            description = "New token and expiry"
                            schema = jsonSchema<InviteUserResponse>()
                        }
                        HttpStatusCode.BadRequest {
                            description = "The invite was already used (INVITATION_ALREADY_USED)"
                            schema = jsonSchema<ProblemDetail>()
                        }
                        HttpStatusCode.Unauthorized {
                            description = "Missing or invalid authentication token"
                            schema = jsonSchema<ProblemDetail>()
                        }
                        HttpStatusCode.Forbidden {
                            description = "Students, or a teacher resending someone else's invite"
                            schema = jsonSchema<ProblemDetail>()
                        }
                        HttpStatusCode.NotFound {
                            description = "No such invite (INVITATION_NOT_FOUND)"
                            schema = jsonSchema<ProblemDetail>()
                        }
                        HttpStatusCode.Conflict {
                            description = "A user with the invite's email now exists (EMAIL_ALREADY_EXISTS)"
                            schema = jsonSchema<ProblemDetail>()
                        }
                    }
                }
            }
        }

        route("/api/v1/password-reset") {
            authenticate {
                post {
                    val principal = call.requirePrincipal()

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
                    val principal = call.requirePrincipal()
                    val userId = call.getPathUUID("userId")

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