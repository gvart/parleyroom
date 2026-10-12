package com.gvart.parleyroom.reminder.routing

import com.gvart.parleyroom.admin.data.FeatureFlag
import com.gvart.parleyroom.admin.service.FeatureFlagService
import com.gvart.parleyroom.common.routing.requirePrincipal
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.reminder.service.TeacherAttentionService
import com.gvart.parleyroom.reminder.service.TeacherReminderSettingsService
import com.gvart.parleyroom.reminder.transfer.AttentionStudent
import com.gvart.parleyroom.reminder.transfer.TeacherReminderSettingsRequest
import com.gvart.parleyroom.reminder.transfer.TeacherReminderSettingsResponse
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.Application
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureReminderRouting() {
    val settings: TeacherReminderSettingsService by dependencies
    val attention: TeacherAttentionService by dependencies
    val flags: FeatureFlagService by dependencies

    // Feature flag `teacherReminders` (D-007): while it is off the settings do not exist.
    val teacherRemindersFlag = createRouteScopedPlugin("TeacherRemindersFeatureFlag") {
        onCall { if (!flags.isEnabled(FeatureFlag.TEACHER_REMINDERS)) throw NotFoundException("Not found") }
    }
    // The attention list serves the reminders' digest and the roadmap's dashboard: either flag makes it exist.
    val attentionFlag = createRouteScopedPlugin("TeacherAttentionFeatureFlag") {
        onCall {
            val enabled = flags.flags()
            if (enabled[FeatureFlag.TEACHER_REMINDERS.key] != true && enabled[FeatureFlag.ROADMAP.key] != true) throw NotFoundException("Not found")
        }
    }

    routing {
        authenticate {
            route("/api/v1/users/me/teacher-reminders") {
                install(teacherRemindersFlag)

                get {
                    call.respond(settings.get(call.requirePrincipal()))
                }.describe {
                    summary = "My teacher reminder settings"
                    description = "Teachers and admins (students 403). Defaults until saved: after-lesson 2 h, before-lesson DAY_BEFORE " +
                        "18:00, weekly digest MONDAY 08:00; in-app always, email off, Telegram on (sent only while linked). Times are " +
                        "in the user's timezone (returned). 404 while the teacherReminders flag is off."
                    responses {
                        HttpStatusCode.OK { schema = jsonSchema<TeacherReminderSettingsResponse>() }
                        HttpStatusCode.Forbidden { schema = jsonSchema<ProblemDetail>() }
                    }
                }

                put<TeacherReminderSettingsRequest> {
                    call.respond(settings.replace(it, call.requirePrincipal()))
                }.describe {
                    summary = "Replace my teacher reminder settings"
                    description = "Teachers and admins (students 403). Replaces every setting; a missing section or field takes its " +
                        "default. afterLesson.hoursAfter 0..48; beforeLesson.mode DAY_BEFORE (at time HH:mm the day before) | " +
                        "HOURS_BEFORE (hoursBefore 1..72); weeklyDigest.weekday MONDAY..SUNDAY, time HH:mm. Field errors are 400 " +
                        "VALIDATION_FAILED with errors[].pointer (e.g. /afterLesson/hoursAfter)."
                    requestBody { schema = jsonSchema<TeacherReminderSettingsRequest>() }
                    responses {
                        HttpStatusCode.OK { schema = jsonSchema<TeacherReminderSettingsResponse>() }
                        HttpStatusCode.BadRequest { description = "VALIDATION_FAILED"; schema = jsonSchema<ProblemDetail>() }
                        HttpStatusCode.Forbidden { schema = jsonSchema<ProblemDetail>() }
                    }
                }
            }

            route("/api/v1/teacher/attention") {
                install(attentionFlag)

                get {
                    call.respond(attention.attention(call.requirePrincipal()))
                }.describe {
                    summary = "Students that need attention"
                    description = "Teachers and admins, own ACTIVE students only (students 403); the weekly digest's content, live. " +
                        "Reasons: BEHIND (goal behind schedule, refId goal, detail \"percent/expected\"), MISMATCH (done milestone the " +
                        "student self-assessed PARTLY / NOT_YET, refId milestone), NOT_WRAPPED_UP (1:1 lesson of the last 14 days without " +
                        "a DONE plan or checked roadmap item, refId lesson), NO_HOMEWORK (none assigned by this teacher in 14 days, at = " +
                        "last one). BEHIND / MISMATCH need the roadmap flag; NOT_WRAPPED_UP lessonPrep, or roadmap for students with a " +
                        "goal. Students without reasons are left out; most severe first (reason order as listed), then more reasons, " +
                        "then name. 404 while both teacherReminders and roadmap are off."
                    responses {
                        HttpStatusCode.OK { schema = jsonSchema<List<AttentionStudent>>() }
                        HttpStatusCode.Forbidden { schema = jsonSchema<ProblemDetail>() }
                    }
                }
            }
        }
    }
}
