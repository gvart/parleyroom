package com.gvart.parleyroom.notification.routing

import com.gvart.parleyroom.common.transfer.PageRequest
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.notification.service.NotificationService
import com.gvart.parleyroom.notification.service.NotificationSseManager
import com.gvart.parleyroom.notification.transfer.MarkViewedRequest
import com.gvart.parleyroom.notification.transfer.NotificationPageResponse
import com.gvart.parleyroom.user.security.UserPrincipal
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

fun Application.configureNotificationRouting() {
    val notificationService: NotificationService by dependencies
    val sseManager: NotificationSseManager by dependencies

    routing {
        authenticate {
            route("/api/v1/notifications") {
                get {
                    val principal = call.principal<UserPrincipal>()!!
                    val pageRequest = PageRequest.from(call)

                    val result = notificationService.getNotifications(principal, pageRequest.page, pageRequest.pageSize)
                    call.respond(HttpStatusCode.OK, result)
                }.describe {
                    summary = "Get notifications"
                    description = "Retrieves paginated notifications for the authenticated user, sorted by created_at descending."
                    parameters {
                        query("page") {
                            description = "Page number (1-based, default 1)"
                            required = false
                        }
                        query("pageSize") {
                            description = "Number of notifications per page (default 20, max 100)"
                            required = false
                        }
                    }
                    responses {
                        HttpStatusCode.OK {
                            description = "Paginated list of notifications"
                            schema = jsonSchema<NotificationPageResponse>()
                        }
                        HttpStatusCode.Unauthorized {
                            description = "Missing or invalid authentication token"
                            schema = jsonSchema<ProblemDetail>()
                        }
                    }
                }

                post<MarkViewedRequest>("/viewed") {
                    val principal = call.principal<UserPrincipal>()!!
                    val ids = it.notificationIds.map(UUID::fromString)

                    notificationService.markAsViewed(ids, principal)
                    call.respond(HttpStatusCode.OK)
                }.describe {
                    summary = "Mark notifications as viewed"
                    description = "Marks one or more notifications as viewed for the authenticated user."
                    requestBody {
                        schema = jsonSchema<MarkViewedRequest>()
                    }
                    responses {
                        HttpStatusCode.OK {
                            description = "Notifications marked as viewed"
                        }
                        HttpStatusCode.Unauthorized {
                            description = "Missing or invalid authentication token"
                            schema = jsonSchema<ProblemDetail>()
                        }
                    }
                }

                get("/stream") {
                    val principal = call.principal<UserPrincipal>()!!
                    val flow = sseManager.subscribe(principal.id)

                    try {
                        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                            flow.collect { notification ->
                                write("data: ${Json.encodeToString(notification)}\n\n")
                                flush()
                            }
                        }
                    } finally {
                        sseManager.unsubscribe(principal.id, flow)
                    }
                }.describe {
                    summary = "SSE notification stream"
                    description = "Server-Sent Events endpoint that pushes new notifications to the connected user in real time."
                    responses {
                        HttpStatusCode.OK {
                            description = "SSE stream of notifications"
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
