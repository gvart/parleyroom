package com.gvart.parleyroom.user

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.user.transfer.UpdateProfileRequest
import com.gvart.parleyroom.user.transfer.UserResponse
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmailPreferencesIntegrationTest : IntegrationTest() {

    @Test
    fun `reminder emails are on by default`() = testApp {
        val client = createJsonClient(this)
        val me = client.get("/api/v1/users/me") { bearerAuth(getStudentToken(client)) }.body<UserResponse>()
        assertTrue(me.emailLessonReminders)
        assertTrue(me.emailHomeworkReminders)
    }

    @Test
    fun `email preferences round-trip through the profile`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val off = client.patch("/api/v1/users/me") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(UpdateProfileRequest(emailLessonReminders = false))
        }
        assertEquals(HttpStatusCode.OK, off.status)
        assertFalse(off.body<UserResponse>().emailLessonReminders)
        assertTrue(off.body<UserResponse>().emailHomeworkReminders)

        client.patch("/api/v1/users/me") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(UpdateProfileRequest(emailLessonReminders = true, emailHomeworkReminders = false))
        }
        val me = client.get("/api/v1/users/me") { bearerAuth(token) }.body<UserResponse>()
        assertTrue(me.emailLessonReminders)
        assertFalse(me.emailHomeworkReminders)
    }
}
