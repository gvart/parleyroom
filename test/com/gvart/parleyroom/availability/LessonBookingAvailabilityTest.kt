package com.gvart.parleyroom.availability

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.availability.data.AvailabilityExceptionType
import com.gvart.parleyroom.availability.transfer.CreateAvailabilityExceptionRequest
import com.gvart.parleyroom.availability.transfer.ReplaceWeeklyAvailabilityRequest
import com.gvart.parleyroom.availability.transfer.WeeklyAvailabilityEntry
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.lesson.transfer.CreateLessonRequest
import com.gvart.parleyroom.user.transfer.UpdateProfileRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
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

class LessonBookingAvailabilityTest : IntegrationTest() {

    private suspend fun setupTeacher(
        client: HttpClient,
        teacherToken: String,
        weekly: List<WeeklyAvailabilityEntry> = emptyList(),
        buffer: Int? = null,
        minNotice: Int? = null,
    ) {
        if (weekly.isNotEmpty()) {
            client.put("/api/v1/teachers/$TEACHER_ID/weekly-availability") {
                contentType(ContentType.Application.Json)
                bearerAuth(teacherToken)
                setBody(ReplaceWeeklyAvailabilityRequest(weekly))
            }
        }
        if (buffer != null || minNotice != null) {
            client.patch("/api/v1/users/me") {
                contentType(ContentType.Application.Json)
                bearerAuth(teacherToken)
                setBody(
                    UpdateProfileRequest(
                        bookingBufferMinutes = buffer,
                        bookingMinNoticeHours = minNotice,
                    )
                )
            }
        }
    }

    private suspend fun book(
        client: HttpClient,
        token: String,
        at: String,
        duration: Int = 60,
    ): HttpResponse = client.post("/api/v1/lessons") {
        contentType(ContentType.Application.Json)
        bearerAuth(token)
        setBody(
            CreateLessonRequest(
                teacherId = TEACHER_ID,
                studentIds = listOf(STUDENT_ID),
                title = "German",
                type = LessonType.ONE_ON_ONE,
                scheduledAt = OffsetDateTime.parse(at),
                durationMinutes = duration,
                topic = "t",
            )
        )
    }

    @Test
    fun `student booking inside BLOCKED window yields AVAILABILITY_SLOT_BLOCKED`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        setupTeacher(client, teacherToken, weekly = listOf(
            WeeklyAvailabilityEntry(dayOfWeek = 1, startTime = "09:00", endTime = "17:00"),
        ))
        client.post("/api/v1/teachers/$TEACHER_ID/availability-exceptions") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(CreateAvailabilityExceptionRequest(
                type = AvailabilityExceptionType.BLOCKED,
                startAt = OffsetDateTime.parse("2028-04-10T10:00:00+02:00"),
                endAt = OffsetDateTime.parse("2028-04-10T11:00:00+02:00"),
            ))
        }

        val response = book(client, studentToken, "2028-04-10T10:30:00+02:00")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val pd = response.body<ProblemDetail>()
        assertEquals("AVAILABILITY_SLOT_BLOCKED", pd.code)
    }

    @Test
    fun `student booking outside weekly yields AVAILABILITY_SLOT_BLOCKED`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        setupTeacher(client, teacherToken, weekly = listOf(
            WeeklyAvailabilityEntry(dayOfWeek = 1, startTime = "09:00", endTime = "12:00"),
        ))

        // 2028-04-10 Monday 14:00 — outside the window.
        val response = book(client, studentToken, "2028-04-10T14:00:00+02:00")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("AVAILABILITY_SLOT_BLOCKED", response.body<ProblemDetail>().code)
    }

    @Test
    fun `student booking inside weekly window succeeds`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        setupTeacher(client, teacherToken, weekly = listOf(
            WeeklyAvailabilityEntry(dayOfWeek = 1, startTime = "09:00", endTime = "12:00"),
        ))

        val response = book(client, studentToken, "2028-04-10T09:00:00+02:00")
        assertEquals(HttpStatusCode.Created, response.status)
    }

    @Test
    fun `admin bypasses availability`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val adminToken = getAdminToken(client)

        setupTeacher(client, teacherToken, weekly = listOf(
            WeeklyAvailabilityEntry(dayOfWeek = 1, startTime = "09:00", endTime = "10:00"),
        ))

        // 15:00 is well outside the 09:00-10:00 window, but admin bypasses.
        val response = book(client, adminToken, "2028-04-10T15:00:00+02:00")
        assertEquals(HttpStatusCode.Created, response.status)
    }

    @Test
    fun `teacher bypasses own availability`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        setupTeacher(client, teacherToken, weekly = listOf(
            WeeklyAvailabilityEntry(dayOfWeek = 1, startTime = "09:00", endTime = "10:00"),
        ))

        val response = book(client, teacherToken, "2028-04-10T15:00:00+02:00")
        assertEquals(HttpStatusCode.Created, response.status)
    }

    @Test
    fun `buffer overlap returns AVAILABILITY_BUFFER_CONFLICT`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        setupTeacher(client, teacherToken,
            weekly = listOf(
                WeeklyAvailabilityEntry(dayOfWeek = 1, startTime = "09:00", endTime = "17:00"),
            ),
            buffer = 15,
        )

        // Teacher creates a lesson 10:00-11:00.
        book(client, teacherToken, "2028-04-10T10:00:00+02:00")

        // Student tries to book 11:00 — exact back-to-back, within buffer.
        val response = book(client, studentToken, "2028-04-10T11:00:00+02:00")
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("AVAILABILITY_BUFFER_CONFLICT", response.body<ProblemDetail>().code)
    }

    @Test
    fun `hard overlap returns AVAILABILITY_OVERLAP`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        setupTeacher(client, teacherToken, weekly = listOf(
            WeeklyAvailabilityEntry(dayOfWeek = 1, startTime = "09:00", endTime = "17:00"),
        ))

        // Existing lesson 10:00-11:00.
        book(client, teacherToken, "2028-04-10T10:00:00+02:00")

        // Student books 10:30 — direct overlap.
        val response = book(client, studentToken, "2028-04-10T10:30:00+02:00")
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("AVAILABILITY_OVERLAP", response.body<ProblemDetail>().code)
    }

    @Test
    fun `default-open when teacher has no availability config`() = testApp {
        val client = createJsonClient(this)
        val studentToken = getStudentToken(client)

        // No weekly, no exceptions, no settings — student should still be able to book.
        val response = book(client, studentToken, "2028-04-10T15:00:00+02:00")
        assertEquals(HttpStatusCode.Created, response.status)
    }

    @Test
    fun `BLOCKED exception still bites when no weekly is set`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        client.post("/api/v1/teachers/$TEACHER_ID/availability-exceptions") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(CreateAvailabilityExceptionRequest(
                type = AvailabilityExceptionType.BLOCKED,
                startAt = OffsetDateTime.parse("2028-04-10T00:00:00+02:00"),
                endAt = OffsetDateTime.parse("2028-04-11T00:00:00+02:00"),
            ))
        }

        val response = book(client, studentToken, "2028-04-10T15:00:00+02:00")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("AVAILABILITY_SLOT_BLOCKED", response.body<ProblemDetail>().code)
    }
}
