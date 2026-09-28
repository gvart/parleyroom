package com.gvart.parleyroom.config

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.gvart.parleyroom.admin.transfer.AdminCreateUserRequest
import com.gvart.parleyroom.admin.transfer.AdminSetPasswordRequest
import com.gvart.parleyroom.admin.transfer.AdminUpdateUserRequest
import com.gvart.parleyroom.ai.transfer.FillMissingRequest
import com.gvart.parleyroom.ai.transfer.GenerateRequest
import com.gvart.parleyroom.ai.transfer.PromptTemplateInput
import com.gvart.parleyroom.ai.transfer.PublishRequest
import com.gvart.parleyroom.ai.transfer.RefineRequest
import com.gvart.parleyroom.ai.transfer.ReviewUpdateRequest
import com.gvart.parleyroom.availability.transfer.CreateAvailabilityExceptionRequest
import com.gvart.parleyroom.availability.transfer.ReplaceWeeklyAvailabilityRequest
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.document.transfer.CreateDocumentRequest
import com.gvart.parleyroom.document.transfer.UpdateDocumentRequest
import com.gvart.parleyroom.document.transfer.DuplicateDocumentRequest
import com.gvart.parleyroom.common.transfer.exception.ConflictException
import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.common.transfer.exception.ServiceUnavailableException
import com.gvart.parleyroom.common.transfer.exception.TooManyRequestsException
import com.gvart.parleyroom.common.transfer.exception.UnauthorizedException
import com.gvart.parleyroom.homework.transfer.CreateAssignmentRequest
import com.gvart.parleyroom.homework.transfer.ReviewDraftRequest
import com.gvart.parleyroom.homework.transfer.ReviewRequest
import com.gvart.parleyroom.homework.transfer.SaveAnswersRequest
import com.gvart.parleyroom.homework.transfer.UpdateAssignmentRequest
import com.gvart.parleyroom.lesson.transfer.CreateLessonRequest
import com.gvart.parleyroom.lesson.transfer.UpdateLessonContentRequest
import com.gvart.parleyroom.lesson.transfer.RescheduleLessonRequest
import com.gvart.parleyroom.notification.transfer.MarkViewedRequest
import com.gvart.parleyroom.registration.transfer.InviteUserRequest
import com.gvart.parleyroom.registration.transfer.RegisterUserRequest
import com.gvart.parleyroom.registration.transfer.ResetPasswordRequest
import com.gvart.parleyroom.vocabulary.transfer.QuickAddVocabRequest
import com.gvart.parleyroom.vocabulary.transfer.VocabDisplaySetting
import com.gvart.parleyroom.vocabulary.transfer.VocabEntryInput
import com.gvart.parleyroom.topic.transfer.CreateTopicRequest
import com.gvart.parleyroom.group.transfer.GroupRequest
import com.gvart.parleyroom.topic.transfer.GrammarTopicRequest
import com.gvart.parleyroom.topic.transfer.UpdateTopicRequest
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.transfer.LogoutRequest
import com.gvart.parleyroom.user.security.AuthLockoutConfig
import com.gvart.parleyroom.user.security.JwtConfig
import com.gvart.parleyroom.user.security.TelegramConfig
import com.gvart.parleyroom.user.security.UserPrincipal
import com.gvart.parleyroom.user.transfer.AuthenticateRequest
import com.gvart.parleyroom.user.transfer.RefreshTokenRequest
import com.gvart.parleyroom.user.transfer.UpdateProfileRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.openapi.openAPI
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.plugins.requestvalidation.RequestValidation
import io.ktor.server.plugins.requestvalidation.RequestValidationException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.ContentTransformationException
import io.ktor.server.request.authorization
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.host
import io.ktor.server.routing.routing
import org.slf4j.event.Level
import java.util.UUID
import kotlin.time.Duration

fun Application.generalConfig() {
    val config = environment.config
    val log = environment.log

    dependencies {
        provide {
            val secret = config.property("jwt.secret").getString()
            if (secret == "secret") {
                log.warn("JWT secret is set to the default value 'secret'. This is insecure and MUST be changed in production!")
            }
            JwtConfig(
                secret = secret,
                issuer = config.property("jwt.issuer").getString(),
                audience = config.property("jwt.audience").getString(),
                realm = config.property("jwt.realm").getString(),
                duration = Duration.parse(config.property("jwt.duration").getString()),
                refreshDuration = Duration.parse(config.property("jwt.refresh_duration").getString()),
            )
        }
        provide {
            AuthLockoutConfig(
                maxFailedAttempts = config.property("authentication.lockout.max_failed_attempts").getString().toInt(),
                lockoutDuration = Duration.parse(config.property("authentication.lockout.duration").getString()),
            )
        }
        provide {
            val token = config.property("telegram.bot_token").getString()
            if (token.isBlank()) {
                log.warn("TELEGRAM_BOT_TOKEN is empty. /token/telegram-miniapp will reject all requests until it is set.")
            }
            TelegramConfig(
                botToken = token,
                initDataMaxAge = Duration.parse(config.property("telegram.init_data_max_age").getString()),
            )
        }
    }

    install(ContentNegotiation) {
        json()
    }

    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Accept)
        allowHeader(HttpHeaders.XRequestId)

        config.property("cors.allowed_origins").getString()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { origin ->
                val (scheme, hostPort) = origin.split("://", limit = 2).let {
                    if (it.size == 2) it[0] to it[1] else "https" to it[0]
                }
                allowHost(hostPort, schemes = listOf(scheme))
            }
    }

    install(CallId) {
        retrieveFromHeader(HttpHeaders.XRequestId)
        generate { UUID.randomUUID().toString() }
        replyToHeader(HttpHeaders.XRequestId)
    }

    install(CallLogging) {
        level = Level.INFO
        callIdMdc("call-id")
        format { call ->
            val status = call.response.status()?.value ?: "?"
            val method = call.request.httpMethod.value
            val path = call.request.path()
            val email = call.request.authorization()?.removePrefix("Bearer ")?.let { runCatching { JWT.decode(it) }.getOrNull() }?.claims?.get("email")?.asString() ?: "no-auth"
            "$status $method $path - $email"
        }
    }

    install(RequestValidation) {
        validate<RegisterUserRequest> { it.validate() }
        validate<AuthenticateRequest> { it.validate() }
        validate<RefreshTokenRequest> { it.validate() }
        validate<ResetPasswordRequest> { it.validate() }
        validate<CreateLessonRequest> { it.validate() }
        validate<RescheduleLessonRequest> { it.validate() }
        validate<InviteUserRequest> { it.validate() }
        validate<QuickAddVocabRequest> { it.validate() }
        validate<VocabEntryInput> { it.validate() }
        validate<VocabDisplaySetting> { it.validate() }
        validate<CreateAssignmentRequest> { it.validate() }
        validate<UpdateAssignmentRequest> { it.validate() }
        validate<SaveAnswersRequest> { it.validate() }
        validate<ReviewDraftRequest> { it.validate() }
        validate<ReviewRequest> { it.validate() }
        validate<LogoutRequest> { it.validate() }
        validate<MarkViewedRequest> { it.validate() }
        validate<UpdateProfileRequest> { it.validate() }
        validate<AdminCreateUserRequest> { it.validate() }
        validate<AdminUpdateUserRequest> { it.validate() }
        validate<AdminSetPasswordRequest> { it.validate() }
        validate<ReplaceWeeklyAvailabilityRequest> { it.validate() }
        validate<CreateAvailabilityExceptionRequest> { it.validate() }
        validate<CreateTopicRequest> { it.validate() }
        validate<UpdateTopicRequest> { it.validate() }
        validate<GrammarTopicRequest> { it.validate() }
        validate<GroupRequest> { it.validate() }
        validate<UpdateLessonContentRequest> { it.validate() }
        validate<CreateDocumentRequest> { it.validate() }
        validate<UpdateDocumentRequest> { it.validate() }
        validate<DuplicateDocumentRequest> { it.validate() }
        validate<GenerateRequest> { it.validate() }
        validate<RefineRequest> { it.validate() }
        validate<ReviewUpdateRequest> { it.validate() }
        validate<PublishRequest> { it.validate() }
        validate<PromptTemplateInput> { it.validate() }
        validate<FillMissingRequest> { it.validate() }
    }


    install(StatusPages) {
        exception<RequestValidationException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ProblemDetail.of(HttpStatusCode.BadRequest, cause.reasons.joinToString(), "VALIDATION_FAILED")
            )
        }
        exception<ContentTransformationException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ProblemDetail.of(HttpStatusCode.BadRequest, cause.message, "MALFORMED_REQUEST"))
        }
        exception<io.ktor.server.plugins.BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ProblemDetail.of(HttpStatusCode.BadRequest, cause.message, "MALFORMED_REQUEST"))
        }

        exception<UnauthorizedException> { call, cause ->
            call.respond(HttpStatusCode.Unauthorized, ProblemDetail.of(HttpStatusCode.Unauthorized, cause.message, cause.code ?: "UNAUTHORIZED"))
        }

        exception<ForbiddenException> { call, cause ->
            call.respond(HttpStatusCode.Forbidden, ProblemDetail.of(HttpStatusCode.Forbidden, cause.message, cause.code ?: "FORBIDDEN"))
        }

        exception<NotFoundException> { call, cause ->
            call.respond(HttpStatusCode.NotFound, ProblemDetail.of(HttpStatusCode.NotFound, cause.message, cause.code ?: "NOT_FOUND"))
        }

        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ProblemDetail.of(HttpStatusCode.BadRequest, cause.message, cause.code ?: "BAD_REQUEST", cause.pointer))
        }

        exception<ConflictException> { call, cause ->
            call.respond(
                HttpStatusCode.Conflict,
                ProblemDetail.of(HttpStatusCode.Conflict, cause.message, cause.code ?: "CONFLICT")
                    .copy(currentRevision = cause.currentRevision, usage = cause.usage),
            )
        }

        exception<TooManyRequestsException> { call, cause ->
            call.respond(
                HttpStatusCode.TooManyRequests,
                ProblemDetail.of(HttpStatusCode.TooManyRequests, cause.message, cause.code ?: "TOO_MANY_REQUESTS")
                    .copy(resetsAt = cause.resetsAt),
            )
        }

        exception<ServiceUnavailableException> { call, cause ->
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ProblemDetail.of(HttpStatusCode.ServiceUnavailable, cause.message, cause.code ?: "SERVICE_UNAVAILABLE"),
            )
        }

        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ProblemDetail.of(HttpStatusCode.BadRequest, cause.message, "BAD_REQUEST"))
        }

        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled exception", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ProblemDetail.of(HttpStatusCode.InternalServerError, "Internal server error", "INTERNAL_ERROR")
            )
        }
    }

    val jwtConfig: JwtConfig by dependencies
    install(Authentication) {
        jwt {
            realm = jwtConfig.realm
            verifier(
                JWT.require(Algorithm.HMAC256(jwtConfig.secret))
                    .withAudience(jwtConfig.audience)
                    .withIssuer(jwtConfig.issuer)
                    .build()
            )

            validate {
                val id = it.payload.getClaim("id").asString()
                val email = it.payload.getClaim("email").asString()
                val role = UserRole.valueOf(it.payload.getClaim("role").asString())

                if (id != null && email != null) UserPrincipal(
                    UUID.fromString(id),
                    email,
                    role,
                ) else null
            }

            challenge { _, _ ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ProblemDetail.of(HttpStatusCode.Unauthorized, "Missing or invalid access token", "UNAUTHORIZED"),
                )
            }
        }
    }

    val openApiEnabled = config.propertyOrNull("application.openapi.enabled")?.getString()?.toBoolean() ?: true
    if (openApiEnabled) {
        val swaggerAllowedHosts = config.property("swagger.allowed_hosts").getString()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        // The same backend pod serves both the LAN ingress (rose.local) and the public
        // Cloudflare tunnel (api.<domain>). The host() selector only registers the docs
        // routes for allowlisted Host headers, so public requests fall through to 404.
        if (swaggerAllowedHosts.isNotEmpty()) {
            routing {
                host(swaggerAllowedHosts) {
                    openAPI("docs.json")
                    swaggerUI("/swagger")
                }
            }
        }
    }
}