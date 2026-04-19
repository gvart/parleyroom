package com.gvart.parleyroom.availability.routing

import com.gvart.parleyroom.availability.service.AvailabilityService
import com.gvart.parleyroom.availability.service.SlotComputationService
import com.gvart.parleyroom.availability.transfer.AvailabilityExceptionResponse
import com.gvart.parleyroom.availability.transfer.AvailableSlotsResponse
import com.gvart.parleyroom.availability.transfer.CreateAvailabilityExceptionRequest
import com.gvart.parleyroom.availability.transfer.ReplaceWeeklyAvailabilityRequest
import com.gvart.parleyroom.availability.transfer.WeeklyAvailabilityEntry
import com.gvart.parleyroom.common.transfer.ProblemDetail
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
import java.time.OffsetDateTime
import java.util.UUID

fun Application.configureAvailabilityRouting() {
    val availabilityService: AvailabilityService by dependencies
    val slotService: SlotComputationService by dependencies

    routing {
        authenticate {
            route("/api/v1/teachers/{teacherId}") {

                route("/weekly-availability") {
                    get {
                        val principal = call.principal<UserPrincipal>()!!
                        val teacherId = UUID.fromString(call.parameters["teacherId"])
                        val entries = availabilityService.getWeekly(teacherId, principal)
                        call.respond(HttpStatusCode.OK, entries)
                    }.describe {
                        summary = "Get weekly availability"
                        description = "Returns the teacher's recurring weekly availability. Accessible to the teacher themselves, admins, or students with an ACTIVE relationship to the teacher."
                        responses {
                            HttpStatusCode.OK { schema = jsonSchema<List<WeeklyAvailabilityEntry>>() }
                            HttpStatusCode.Forbidden { schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    put<ReplaceWeeklyAvailabilityRequest> { request ->
                        val principal = call.principal<UserPrincipal>()!!
                        val teacherId = UUID.fromString(call.parameters["teacherId"])
                        val entries = availabilityService.replaceWeekly(teacherId, request, principal)
                        call.respond(HttpStatusCode.OK, entries)
                    }.describe {
                        summary = "Replace weekly availability"
                        description = "Full-replace the teacher's weekly schedule atomically. All previous rows for this teacher are deleted and replaced with the provided entries. Admin or the teacher themselves."
                        requestBody { schema = jsonSchema<ReplaceWeeklyAvailabilityRequest>() }
                        responses {
                            HttpStatusCode.OK { schema = jsonSchema<List<WeeklyAvailabilityEntry>>() }
                            HttpStatusCode.BadRequest { schema = jsonSchema<ProblemDetail>() }
                            HttpStatusCode.Forbidden { schema = jsonSchema<ProblemDetail>() }
                        }
                    }
                }

                route("/availability-exceptions") {
                    get {
                        val principal = call.principal<UserPrincipal>()!!
                        val teacherId = UUID.fromString(call.parameters["teacherId"])
                        val from = call.request.queryParameters["from"]?.let(OffsetDateTime::parse)
                        val to = call.request.queryParameters["to"]?.let(OffsetDateTime::parse)
                        val exceptions = availabilityService.getExceptions(teacherId, from, to, principal)
                        call.respond(HttpStatusCode.OK, exceptions)
                    }.describe {
                        summary = "List availability exceptions"
                        description = "Returns one-off exceptions (BLOCKED or AVAILABLE) in an optional date window. Same auth rules as weekly-availability GET."
                        parameters {
                            query("from") { required = false }
                            query("to") { required = false }
                        }
                        responses {
                            HttpStatusCode.OK { schema = jsonSchema<List<AvailabilityExceptionResponse>>() }
                            HttpStatusCode.Forbidden { schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    post<CreateAvailabilityExceptionRequest> { request ->
                        val principal = call.principal<UserPrincipal>()!!
                        val teacherId = UUID.fromString(call.parameters["teacherId"])
                        val created = availabilityService.createException(teacherId, request, principal)
                        call.respond(HttpStatusCode.Created, created)
                    }.describe {
                        summary = "Create availability exception"
                        description = "Adds a one-off exception. Type BLOCKED marks time off (vacation, sick, etc.); type AVAILABLE adds bonus slots outside the weekly schedule."
                        requestBody { schema = jsonSchema<CreateAvailabilityExceptionRequest>() }
                        responses {
                            HttpStatusCode.Created { schema = jsonSchema<AvailabilityExceptionResponse>() }
                            HttpStatusCode.BadRequest { schema = jsonSchema<ProblemDetail>() }
                            HttpStatusCode.Forbidden { schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    delete("/{exceptionId}") {
                        val principal = call.principal<UserPrincipal>()!!
                        val teacherId = UUID.fromString(call.parameters["teacherId"])
                        val exceptionId = UUID.fromString(call.parameters["exceptionId"])
                        availabilityService.deleteException(teacherId, exceptionId, principal)
                        call.respond(HttpStatusCode.NoContent)
                    }.describe {
                        summary = "Delete availability exception"
                        description = "Deletes a one-off exception. Teacher or admin only."
                        responses {
                            HttpStatusCode.NoContent { description = "Deleted" }
                            HttpStatusCode.NotFound { schema = jsonSchema<ProblemDetail>() }
                            HttpStatusCode.Forbidden { schema = jsonSchema<ProblemDetail>() }
                        }
                    }
                }

                get("/available-slots") {
                    val principal = call.principal<UserPrincipal>()!!
                    val teacherId = UUID.fromString(call.parameters["teacherId"])
                    val from = OffsetDateTime.parse(call.request.queryParameters["from"]!!)
                    val to = OffsetDateTime.parse(call.request.queryParameters["to"]!!)
                    val duration = (call.request.queryParameters["durationMinutes"] ?: "60").toInt()

                    val response = slotService.getSlots(
                        teacherId = teacherId,
                        from = from,
                        to = to,
                        durationMinutes = duration,
                        now = OffsetDateTime.now(),
                        principal = principal,
                    )
                    call.respond(HttpStatusCode.OK, response)
                }.describe {
                    summary = "Get available booking slots"
                    description = "Computes bookable slots for the teacher in [from, to] of the given duration. Applies weekly availability + one-off exceptions, min-notice window, and excludes existing lessons widened by buffer. Auth: admin, teacher themselves, or student with ACTIVE relationship."
                    parameters {
                        path("teacherId") { description = "UUID of the teacher" }
                        query("from") { description = "Window start (ISO 8601 with offset)" }
                        query("to") { description = "Window end (ISO 8601 with offset)" }
                        query("durationMinutes") {
                            description = "Slot duration, default 60"
                            required = false
                        }
                    }
                    responses {
                        HttpStatusCode.OK { schema = jsonSchema<AvailableSlotsResponse>() }
                        HttpStatusCode.BadRequest { schema = jsonSchema<ProblemDetail>() }
                        HttpStatusCode.Forbidden { schema = jsonSchema<ProblemDetail>() }
                    }
                }
            }
        }
    }
}
