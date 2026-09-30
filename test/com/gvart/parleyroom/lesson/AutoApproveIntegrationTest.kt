package com.gvart.parleyroom.lesson

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.lesson.data.LessonStudentStatus
import com.gvart.parleyroom.lesson.transfer.CreateLessonRequest
import com.gvart.parleyroom.lesson.transfer.JoinLessonResponse
import com.gvart.parleyroom.lesson.transfer.LessonResponse
import com.gvart.parleyroom.lesson.transfer.OpenClubResponse
import com.gvart.parleyroom.notification.data.NotificationType
import com.gvart.parleyroom.notification.transfer.NotificationPageResponse
import com.gvart.parleyroom.user.transfer.UpdateProfileRequest
import com.gvart.parleyroom.user.transfer.UserResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoApproveIntegrationTest : IntegrationTest() {

    private suspend fun patchMe(client: HttpClient, token: String, body: UpdateProfileRequest): HttpResponse =
        client.patch("/api/v1/users/me") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(body)
        }

    private suspend fun createLesson(
        client: HttpClient,
        token: String,
        type: LessonType = LessonType.ONE_ON_ONE,
        studentIds: List<String> = listOf(STUDENT_ID),
        maxParticipants: Int? = null,
        scheduledAt: String = "2027-04-10T10:00:00+02:00",
    ): HttpResponse = client.post("/api/v1/lessons") {
        contentType(ContentType.Application.Json)
        bearerAuth(token)
        setBody(
            CreateLessonRequest(
                teacherId = TEACHER_ID,
                studentIds = studentIds,
                title = "Lesson",
                type = type,
                scheduledAt = OffsetDateTime.parse(scheduledAt),
                durationMinutes = 60,
                topic = "Topic",
                maxParticipants = maxParticipants,
            )
        )
    }

    private suspend fun notificationTypes(client: HttpClient, token: String): List<NotificationType> =
        client.get("/api/v1/notifications") { bearerAuth(token) }
            .body<NotificationPageResponse>().notifications.map { it.type }

    private fun linkStudent2() = transaction {
        exec(
            "INSERT INTO teacher_students (teacher_id, student_id, lesson_types, status, started_at) " +
                "VALUES ('$TEACHER_ID', '$STUDENT_2_ID', '{ONE_ON_ONE}', 'ACTIVE', now())"
        )
    }

    // -- settings --

    @Test
    fun `approval settings default off and teachers can change them`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)

        val before = client.get("/api/v1/users/me") { bearerAuth(teacher) }.body<UserResponse>()
        assertFalse(before.autoConfirmBookings)
        assertFalse(before.autoAcceptClubJoins)

        val patched = patchMe(client, teacher, UpdateProfileRequest(autoConfirmBookings = true))
        assertEquals(HttpStatusCode.OK, patched.status)
        assertTrue(patched.body<UserResponse>().autoConfirmBookings)
        assertFalse(patched.body<UserResponse>().autoAcceptClubJoins)

        patchMe(client, teacher, UpdateProfileRequest(autoAcceptClubJoins = true))
        val after = client.get("/api/v1/users/me") { bearerAuth(teacher) }.body<UserResponse>()
        assertTrue(after.autoConfirmBookings)
        assertTrue(after.autoAcceptClubJoins)
    }

    @Test
    fun `students cannot set approval settings`() = testApp {
        val client = createJsonClient(this)
        val response = patchMe(client, getStudentToken(client), UpdateProfileRequest(autoAcceptClubJoins = true))
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("APPROVAL_SETTINGS_TEACHERS_ONLY", response.body<ProblemDetail>().code)
    }

    // -- 1:1 bookings --

    @Test
    fun `booking stays a request while auto-confirm is off`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)

        val lesson = createLesson(client, getStudentToken(client)).body<LessonResponse>()

        assertEquals(LessonStatus.REQUEST, lesson.status)
        assertTrue(NotificationType.LESSON_REQUESTED in notificationTypes(client, teacher))
    }

    @Test
    fun `auto-confirm turns a valid booking into a confirmed lesson and tells the teacher`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val student = getStudentToken(client)
        patchMe(client, teacher, UpdateProfileRequest(autoConfirmBookings = true))

        val lesson = createLesson(client, student).body<LessonResponse>()

        assertEquals(LessonStatus.CONFIRMED, lesson.status)
        val teacherNotes = notificationTypes(client, teacher)
        assertTrue(NotificationType.LESSON_BOOKED in teacherNotes)
        assertFalse(NotificationType.LESSON_REQUESTED in teacherNotes)
        assertTrue(notificationTypes(client, student).isEmpty(), "student isn't told about their own booking")
    }

    @Test
    fun `auto-confirm still rejects a booking that clashes`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        patchMe(client, teacher, UpdateProfileRequest(autoConfirmBookings = true))
        createLesson(client, teacher)

        val response = createLesson(client, getStudentToken(client))

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("AVAILABILITY_OVERLAP", response.body<ProblemDetail>().code)
    }

    // -- club joins --

    @Test
    fun `club join stays a request while auto-accept is off`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val club = createLesson(client, teacher, LessonType.SPEAKING_CLUB, emptyList(), 2).body<LessonResponse>()

        val joined = client.post("/api/v1/lessons/${club.id}/join") { bearerAuth(getStudentToken(client)) }
        assertEquals(LessonStudentStatus.REQUESTED, joined.body<JoinLessonResponse>().status)

        val seen = client.get("/api/v1/lessons/open-clubs") { bearerAuth(getStudentToken(client)) }
            .body<List<OpenClubResponse>>().single()
        assertEquals(LessonStudentStatus.REQUESTED, seen.myStatus)
        assertTrue(NotificationType.JOIN_REQUESTED in notificationTypes(client, teacher))
    }

    @Test
    fun `auto-accept confirms a join with a free spot and notifies both sides`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val student = getStudentToken(client)
        val student2 = getStudent2Token(client)
        linkStudent2()
        patchMe(client, teacher, UpdateProfileRequest(autoAcceptClubJoins = true))
        val club = createLesson(client, teacher, LessonType.SPEAKING_CLUB, emptyList(), 1).body<LessonResponse>()

        val joined = client.post("/api/v1/lessons/${club.id}/join") { bearerAuth(student) }
        assertEquals(HttpStatusCode.Created, joined.status)
        assertEquals(LessonStudentStatus.CONFIRMED, joined.body<JoinLessonResponse>().status)

        val seen = client.get("/api/v1/lessons/open-clubs") { bearerAuth(student) }
            .body<List<OpenClubResponse>>().single()
        assertEquals(LessonStudentStatus.CONFIRMED, seen.myStatus)
        assertTrue(NotificationType.JOIN_ACCEPTED in notificationTypes(client, student))
        val teacherNotes = notificationTypes(client, teacher)
        assertTrue(NotificationType.CLUB_JOINED in teacherNotes)
        assertFalse(NotificationType.JOIN_REQUESTED in teacherNotes)

        // No free spot left: auto-accept doesn't bypass capacity.
        val full = client.post("/api/v1/lessons/${club.id}/join") { bearerAuth(student2) }
        assertEquals(HttpStatusCode.Conflict, full.status)
        assertEquals("CLUB_FULL", full.body<ProblemDetail>().code)
    }
}
