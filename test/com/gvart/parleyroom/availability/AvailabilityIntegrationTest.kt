package com.gvart.parleyroom.availability

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.availability.data.AvailabilityExceptionType
import com.gvart.parleyroom.availability.transfer.AvailabilityExceptionResponse
import com.gvart.parleyroom.availability.transfer.AvailableSlotsResponse
import com.gvart.parleyroom.availability.transfer.CreateAvailabilityExceptionRequest
import com.gvart.parleyroom.availability.transfer.ReplaceWeeklyAvailabilityRequest
import com.gvart.parleyroom.availability.transfer.WeeklyAvailabilityEntry
import com.gvart.parleyroom.user.transfer.UpdateProfileRequest
import com.gvart.parleyroom.user.transfer.UserResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

private fun assertOffsetEquals(expected: String, actual: OffsetDateTime) {
    val exp = OffsetDateTime.parse(expected)
    assertTrue(
        actual.toInstant() == exp.toInstant(),
        "Expected ${exp.toInstant()} ($expected) but was ${actual.toInstant()} ($actual)"
    )
}

class AvailabilityIntegrationTest : IntegrationTest() {

    // ---- helpers ----

    private suspend fun putWeekly(
        client: HttpClient,
        token: String,
        teacherId: String,
        entries: List<WeeklyAvailabilityEntry>,
    ): HttpResponse = client.put("/api/v1/teachers/$teacherId/weekly-availability") {
        contentType(ContentType.Application.Json)
        bearerAuth(token)
        setBody(ReplaceWeeklyAvailabilityRequest(entries))
    }

    private suspend fun getWeekly(
        client: HttpClient,
        token: String,
        teacherId: String,
    ): List<WeeklyAvailabilityEntry> = client.get("/api/v1/teachers/$teacherId/weekly-availability") {
        bearerAuth(token)
    }.body()

    private suspend fun postException(
        client: HttpClient,
        token: String,
        teacherId: String,
        request: CreateAvailabilityExceptionRequest,
    ): HttpResponse = client.post("/api/v1/teachers/$teacherId/availability-exceptions") {
        contentType(ContentType.Application.Json)
        bearerAuth(token)
        setBody(request)
    }

    private suspend fun getSlots(
        client: HttpClient,
        token: String,
        teacherId: String,
        from: String,
        to: String,
        duration: Int = 60,
    ): HttpResponse {
        val encFrom = from.replace("+", "%2B")
        val encTo = to.replace("+", "%2B")
        return client.get("/api/v1/teachers/$teacherId/available-slots?from=$encFrom&to=$encTo&durationMinutes=$duration") {
            bearerAuth(token)
        }
    }

    // ---- CRUD: weekly availability ----

    @Test
    fun `teacher can replace and fetch weekly availability`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val putResponse = putWeekly(
            client, token, TEACHER_ID,
            listOf(
                WeeklyAvailabilityEntry(dayOfWeek = 1, startTime = "09:00", endTime = "12:00"),
                WeeklyAvailabilityEntry(dayOfWeek = 1, startTime = "14:00", endTime = "18:00"),
                WeeklyAvailabilityEntry(dayOfWeek = 3, startTime = "10:00", endTime = "15:00"),
            ),
        )
        assertEquals(HttpStatusCode.OK, putResponse.status)

        val entries = getWeekly(client, token, TEACHER_ID)
        assertEquals(3, entries.size)
        assertEquals(1, entries[0].dayOfWeek)
        assertEquals(1, entries[1].dayOfWeek)
        assertEquals(3, entries[2].dayOfWeek)
        // sorted by (day, start)
        assertTrue(entries[0].startTime < entries[1].startTime)
    }

    @Test
    fun `replacing weekly deletes old rows`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        putWeekly(client, token, TEACHER_ID, listOf(
            WeeklyAvailabilityEntry(dayOfWeek = 1, startTime = "09:00", endTime = "12:00"),
        ))
        assertEquals(1, getWeekly(client, token, TEACHER_ID).size)

        putWeekly(client, token, TEACHER_ID, listOf(
            WeeklyAvailabilityEntry(dayOfWeek = 2, startTime = "10:00", endTime = "11:00"),
        ))
        val after = getWeekly(client, token, TEACHER_ID)
        assertEquals(1, after.size)
        assertEquals(2, after.first().dayOfWeek)
    }

    @Test
    fun `PUT weekly rejects overlapping ranges within a day`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val response = putWeekly(client, token, TEACHER_ID, listOf(
            WeeklyAvailabilityEntry(dayOfWeek = 1, startTime = "09:00", endTime = "12:00"),
            WeeklyAvailabilityEntry(dayOfWeek = 1, startTime = "11:00", endTime = "13:00"),
        ))
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `PUT weekly rejects start not before end`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val response = putWeekly(client, token, TEACHER_ID, listOf(
            WeeklyAvailabilityEntry(dayOfWeek = 1, startTime = "12:00", endTime = "10:00"),
        ))
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `student cannot modify teacher weekly availability`() = testApp {
        val client = createJsonClient(this)
        val studentToken = getStudentToken(client)

        val response = putWeekly(client, studentToken, TEACHER_ID, listOf(
            WeeklyAvailabilityEntry(dayOfWeek = 1, startTime = "09:00", endTime = "12:00"),
        ))
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // ---- CRUD: exceptions ----

    @Test
    fun `teacher adds and deletes exception`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val post = postException(client, token, TEACHER_ID, CreateAvailabilityExceptionRequest(
            type = AvailabilityExceptionType.BLOCKED,
            startAt = OffsetDateTime.parse("2027-05-10T00:00:00+02:00"),
            endAt = OffsetDateTime.parse("2027-05-12T00:00:00+02:00"),
            reason = "Vacation",
        ))
        assertEquals(HttpStatusCode.Created, post.status)
        val created = post.body<AvailabilityExceptionResponse>()

        val list = client.get("/api/v1/teachers/$TEACHER_ID/availability-exceptions") {
            bearerAuth(token)
        }.body<List<AvailabilityExceptionResponse>>()
        assertEquals(1, list.size)
        assertEquals("Vacation", list.first().reason)

        val del = client.delete("/api/v1/teachers/$TEACHER_ID/availability-exceptions/${created.id}") {
            bearerAuth(token)
        }
        assertEquals(HttpStatusCode.NoContent, del.status)

        val listAfter = client.get("/api/v1/teachers/$TEACHER_ID/availability-exceptions") {
            bearerAuth(token)
        }.body<List<AvailabilityExceptionResponse>>()
        assertTrue(listAfter.isEmpty())
    }

    @Test
    fun `exception requires startAt before endAt`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val response = postException(client, token, TEACHER_ID, CreateAvailabilityExceptionRequest(
            type = AvailabilityExceptionType.BLOCKED,
            startAt = OffsetDateTime.parse("2027-05-12T00:00:00+02:00"),
            endAt = OffsetDateTime.parse("2027-05-10T00:00:00+02:00"),
        ))
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ---- booking settings on users/me ----

    @Test
    fun `teacher patches booking settings via users me`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val response = client.patch("/api/v1/users/me") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(UpdateProfileRequest(
                timezone = "Europe/Berlin",
                bookingBufferMinutes = 15,
                bookingMinNoticeHours = 2,
            ))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<UserResponse>()
        assertEquals("Europe/Berlin", body.timezone)
        assertEquals(15, body.bookingBufferMinutes)
        assertEquals(2, body.bookingMinNoticeHours)
    }

    @Test
    fun `invalid timezone is rejected`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val response = client.patch("/api/v1/users/me") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(UpdateProfileRequest(timezone = "Not/A/Zone"))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ---- slot computation ----

    @Test
    fun `no weekly set yields empty slots`() = testApp {
        val client = createJsonClient(this)
        val studentToken = getStudentToken(client)

        val r = getSlots(
            client, studentToken, TEACHER_ID,
            from = "2028-04-10T00:00:00+02:00",  // Monday
            to = "2028-04-10T23:59:00+02:00",
        )
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.body<AvailableSlotsResponse>().slots.isEmpty())
    }

    @Test
    fun `weekly Mon 9-12 produces three hourly slots`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        putWeekly(client, teacherToken, TEACHER_ID, listOf(
            WeeklyAvailabilityEntry(dayOfWeek = 1, startTime = "09:00", endTime = "12:00"),
        ))

        // 2028-04-10 is a Monday in UTC and Europe/Berlin (MESZ = +02:00).
        val r = getSlots(
            client, studentToken, TEACHER_ID,
            from = "2028-04-10T00:00:00+02:00",
            to = "2028-04-10T23:59:00+02:00",
        )
        val slots = r.body<AvailableSlotsResponse>().slots
        assertEquals(3, slots.size)
        assertOffsetEquals("2028-04-10T09:00:00+02:00", slots[0].start)
        assertOffsetEquals("2028-04-10T10:00:00+02:00", slots[1].start)
        assertOffsetEquals("2028-04-10T11:00:00+02:00", slots[2].start)
    }

    @Test
    fun `BLOCKED exception removes matching slot`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        putWeekly(client, teacherToken, TEACHER_ID, listOf(
            WeeklyAvailabilityEntry(dayOfWeek = 1, startTime = "09:00", endTime = "12:00"),
        ))
        postException(client, teacherToken, TEACHER_ID, CreateAvailabilityExceptionRequest(
            type = AvailabilityExceptionType.BLOCKED,
            startAt = OffsetDateTime.parse("2028-04-10T10:00:00+02:00"),
            endAt = OffsetDateTime.parse("2028-04-10T11:00:00+02:00"),
        ))

        val slots = getSlots(
            client, studentToken, TEACHER_ID,
            from = "2028-04-10T00:00:00+02:00",
            to = "2028-04-10T23:59:00+02:00",
        ).body<AvailableSlotsResponse>().slots
        assertEquals(2, slots.size)
        assertOffsetEquals("2028-04-10T09:00:00+02:00", slots[0].start)
        assertOffsetEquals("2028-04-10T11:00:00+02:00", slots[1].start)
    }

    @Test
    fun `AVAILABLE exception adds slots outside weekly`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        // 2028-04-16 is a Sunday — no weekly entry, but add an AVAILABLE override.
        putWeekly(client, teacherToken, TEACHER_ID, listOf(
            WeeklyAvailabilityEntry(dayOfWeek = 1, startTime = "09:00", endTime = "12:00"),
        ))
        postException(client, teacherToken, TEACHER_ID, CreateAvailabilityExceptionRequest(
            type = AvailabilityExceptionType.AVAILABLE,
            startAt = OffsetDateTime.parse("2028-04-16T10:00:00+02:00"),
            endAt = OffsetDateTime.parse("2028-04-16T12:00:00+02:00"),
        ))

        val slots = getSlots(
            client, studentToken, TEACHER_ID,
            from = "2028-04-16T00:00:00+02:00",
            to = "2028-04-16T23:59:00+02:00",
        ).body<AvailableSlotsResponse>().slots
        assertEquals(2, slots.size)
    }

    @Test
    fun `min notice cuts off near slots`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        // Teacher available every Monday 09:00–12:00, min notice 24 hours.
        putWeekly(client, teacherToken, TEACHER_ID, listOf(
            WeeklyAvailabilityEntry(dayOfWeek = 1, startTime = "09:00", endTime = "12:00"),
        ))
        client.patch("/api/v1/users/me") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(UpdateProfileRequest(bookingMinNoticeHours = 24))
        }

        // Query a monday far in the future to guarantee it's > 24h out.
        val slots = getSlots(
            client, studentToken, TEACHER_ID,
            from = "2028-04-10T00:00:00+02:00",
            to = "2028-04-10T23:59:00+02:00",
        ).body<AvailableSlotsResponse>().slots
        assertEquals(3, slots.size)
    }

    @Test
    fun `existing lesson plus buffer removes adjacent slots`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        putWeekly(client, teacherToken, TEACHER_ID, listOf(
            WeeklyAvailabilityEntry(dayOfWeek = 1, startTime = "09:00", endTime = "13:00"),
        ))
        client.patch("/api/v1/users/me") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(UpdateProfileRequest(bookingBufferMinutes = 15))
        }

        // Teacher creates a lesson Mon 10:00 for 60min.
        val createLessonBody = """
            {"teacherId":"$TEACHER_ID","studentIds":["$STUDENT_ID"],
            "title":"X","type":"ONE_ON_ONE","scheduledAt":"2028-04-10T10:00:00+02:00",
            "durationMinutes":60,"topic":"t"}
        """.trimIndent()
        val createResp = client.post("/api/v1/lessons") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(createLessonBody)
        }
        assertEquals(HttpStatusCode.Created, createResp.status)

        val slots = getSlots(
            client, studentToken, TEACHER_ID,
            from = "2028-04-10T00:00:00+02:00",
            to = "2028-04-10T23:59:00+02:00",
        ).body<AvailableSlotsResponse>().slots
        // Weekly 09-13 = window [9:00, 13:00].
        // Lesson 10:00-11:00 + buffer 15 eats [9:45, 11:15], leaving [9:00, 9:45] and [11:15, 13:00].
        // 60-min slots: [9:00-9:45] too short (9:00+60=10:00 > 9:45). [11:15, 13:00] fits [11:15-12:15].
        // Next slot 12:15+60=13:15 > 13:00, break. Result: 1 slot at 11:15.
        assertEquals(1, slots.size)
        assertOffsetEquals("2028-04-10T11:15:00+02:00", slots[0].start)
    }

    @Test
    fun `DST spring-forward yields correct slot count`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        // 2028-03-26 is a Sunday. At 02:00 Europe/Berlin clocks jump to 03:00.
        // Weekly Sun 02:00-04:00 therefore yields a single hour (03-04) locally,
        // i.e. exactly one 60-min slot.
        putWeekly(client, teacherToken, TEACHER_ID, listOf(
            WeeklyAvailabilityEntry(dayOfWeek = 7, startTime = "02:00", endTime = "04:00"),
        ))

        val slots = getSlots(
            client, studentToken, TEACHER_ID,
            from = "2028-03-26T00:00:00+01:00",
            to = "2028-03-26T23:59:00+02:00",
        ).body<AvailableSlotsResponse>().slots
        assertEquals(1, slots.size)
    }

    @Test
    fun `unlinked student cannot fetch slots`() = testApp {
        val client = createJsonClient(this)
        val token = getStudent2Token(client)  // not linked to teacher

        val r = getSlots(
            client, token, TEACHER_ID,
            from = "2028-04-10T00:00:00+02:00",
            to = "2028-04-10T23:59:00+02:00",
        )
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    @Test
    fun `public calendar includes availability`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        putWeekly(client, teacherToken, TEACHER_ID, listOf(
            WeeklyAvailabilityEntry(dayOfWeek = 1, startTime = "09:00", endTime = "12:00"),
        ))

        val response = client.get("/api/v1/public/teachers/$TEACHER_ID/calendar")
        assertEquals(HttpStatusCode.OK, response.status)
        val json = response.body<com.gvart.parleyroom.lesson.transfer.PublicCalendarResponse>()
        val availability = assertNotNull(json.availability)
        assertEquals(1, availability.weekly.size)
        assertEquals("Europe/Berlin", availability.timezone)
    }
}
