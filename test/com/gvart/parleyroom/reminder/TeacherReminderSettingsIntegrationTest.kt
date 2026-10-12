package com.gvart.parleyroom.reminder

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.ai.TEACHER
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.email.service.UnsubscribeTokens
import com.gvart.parleyroom.goal.GoalFixtures
import com.gvart.parleyroom.email.transfer.EmailCategory
import com.gvart.parleyroom.email.transfer.UnsubscribeRequest
import com.gvart.parleyroom.email.transfer.UnsubscribeResponse
import com.gvart.parleyroom.reminder.transfer.AfterLessonSettings
import com.gvart.parleyroom.reminder.transfer.BeforeLessonSettings
import com.gvart.parleyroom.reminder.transfer.TeacherReminderSettingsResponse
import com.gvart.parleyroom.reminder.transfer.WeeklyDigestSettings
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.plugins.di.dependencies
import kotlin.test.Test
import kotlin.test.assertEquals

class TeacherReminderSettingsIntegrationTest : IntegrationTest() {

    private val path = "/api/v1/users/me/teacher-reminders"

    private suspend fun HttpClient.putJson(token: String, json: String): HttpResponse = put(path) {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(json)
    }

    @Test
    fun `routes are 404 while the flag is off`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        assertEquals(HttpStatusCode.NotFound, client.get(path) { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.NotFound, client.putJson(token, "{}").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/teacher/attention") { bearerAuth(token) }.status)
    }

    @Test
    fun `defaults until saved, then a full replace that reads back`() = testApp {
        startApplication()
        TeacherReminderFixtures.flags("teacherReminders")
        TeacherReminderFixtures.setUser(TEACHER, timezone = "America/New_York")
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val defaults = client.get(path) { bearerAuth(token) }.body<TeacherReminderSettingsResponse>()
        assertEquals(AfterLessonSettings(true, 2, email = false, telegram = true), defaults.afterLesson)
        assertEquals(BeforeLessonSettings(true, "DAY_BEFORE", "18:00", 3, email = false, telegram = true), defaults.beforeLesson)
        assertEquals(WeeklyDigestSettings(true, "MONDAY", "08:00", email = false, telegram = true), defaults.weeklyDigest)
        assertEquals("America/New_York", defaults.timezone)
        assertEquals(false, defaults.telegramLinked)

        val saved = client.putJson(token, """
            {"afterLesson": {"enabled": false, "hoursAfter": 0, "email": true},
             "beforeLesson": {"mode": "HOURS_BEFORE", "hoursBefore": 12, "telegram": false},
             "weeklyDigest": {"weekday": "FRIDAY", "time": "17:30", "email": true}}
        """).body<TeacherReminderSettingsResponse>()
        assertEquals(AfterLessonSettings(false, 0, email = true, telegram = true), saved.afterLesson)
        assertEquals(BeforeLessonSettings(true, "HOURS_BEFORE", "18:00", 12, email = false, telegram = false), saved.beforeLesson)
        assertEquals(WeeklyDigestSettings(true, "FRIDAY", "17:30", email = true, telegram = true), saved.weeklyDigest)
        assertEquals(saved, client.get(path) { bearerAuth(token) }.body<TeacherReminderSettingsResponse>())

        // PUT replaces: an empty body is the defaults again.
        assertEquals(defaults, client.putJson(token, "{}").body<TeacherReminderSettingsResponse>())
    }

    @Test
    fun `invalid values are field errors with pointers`() = testApp {
        startApplication()
        TeacherReminderFixtures.flags("teacherReminders")
        val client = createJsonClient(this)
        val response = client.putJson(getTeacherToken(client), """
            {"afterLesson": {"hoursAfter": 49},
             "beforeLesson": {"mode": "WEEK_BEFORE", "time": "25:00", "hoursBefore": 0},
             "weeklyDigest": {"weekday": "FUNDAY", "time": "8am"}}
        """)
        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        val problem = response.body<ProblemDetail>()
        assertEquals("VALIDATION_FAILED", problem.code)
        assertEquals(
            setOf(
                "/afterLesson/hoursAfter", "/beforeLesson/mode", "/beforeLesson/time", "/beforeLesson/hoursBefore",
                "/weeklyDigest/weekday", "/weeklyDigest/time",
            ),
            problem.errors!!.mapNotNull { it.pointer }.toSet(),
        )
    }

    @Test
    fun `students get 403`() = testApp {
        startApplication()
        TeacherReminderFixtures.flags("teacherReminders")
        val client = createJsonClient(this)
        val token = getStudentToken(client)
        assertEquals(HttpStatusCode.Forbidden, client.get(path) { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.putJson(token, "{}").status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/teacher/attention") { bearerAuth(token) }.status)
    }

    @Test
    fun `the unsubscribe link of a teacher reminder switches every teacher reminder email off`() = testApp {
        startApplication()
        TeacherReminderFixtures.flags("teacherReminders")
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        client.putJson(token, """{"afterLesson": {"email": true}, "weeklyDigest": {"email": true, "weekday": "SUNDAY"}}""")

        val unsubscribe = application.dependencies.resolve<UnsubscribeTokens>().token(TEACHER, EmailCategory.TEACHER_REMINDERS)
        val response = client.post("/api/v1/email/unsubscribe") {
            contentType(ContentType.Application.Json)
            setBody(UnsubscribeRequest(unsubscribe))
        }
        assertEquals(EmailCategory.TEACHER_REMINDERS, response.body<UnsubscribeResponse>().category)

        val after = client.get(path) { bearerAuth(token) }.body<TeacherReminderSettingsResponse>()
        assertEquals(listOf(false, false, false), listOf(after.afterLesson.email, after.beforeLesson.email, after.weeklyDigest.email))
        assertEquals("SUNDAY", after.weeklyDigest.weekday)

        // A teacher who never saved settings gets the defaults with email off.
        GoalFixtures.secondTeacher()
        val other = application.dependencies.resolve<UnsubscribeTokens>().token(GoalFixtures.TEACHER_2, EmailCategory.TEACHER_REMINDERS)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/email/unsubscribe") {
            contentType(ContentType.Application.Json)
            setBody(UnsubscribeRequest(other))
        }.status)
        val second = client.get(path) { bearerAuth(getToken(client, "teacher2@test.com")) }.body<TeacherReminderSettingsResponse>()
        assertEquals(WeeklyDigestSettings(email = false), second.weeklyDigest)
        assertEquals(AfterLessonSettings(email = false), second.afterLesson)
    }
}
