package com.gvart.parleyroom.lesson

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.availability.service.ScheduleWarning
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.lesson.data.LessonEventTable
import com.gvart.parleyroom.lesson.data.LessonEventType
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.lesson.transfer.CreateLessonRequest
import com.gvart.parleyroom.lesson.transfer.LessonResponse
import com.gvart.parleyroom.lesson.transfer.MoveLessonRequest
import com.gvart.parleyroom.lesson.transfer.MoveLessonResponse
import com.gvart.parleyroom.lesson.transfer.RescheduleLessonRequest
import com.gvart.parleyroom.notification.data.NotificationType
import com.gvart.parleyroom.notification.transfer.NotificationPageResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LessonMoveIntegrationTest : IntegrationTest() {

    private val originalTime = OffsetDateTime.parse("2027-04-12T10:00:00+02:00")

    private suspend fun createLesson(
        client: HttpClient,
        token: String,
        scheduledAt: OffsetDateTime = originalTime,
    ): LessonResponse = client.post("/api/v1/lessons") {
        contentType(ContentType.Application.Json)
        bearerAuth(token)
        setBody(
            CreateLessonRequest(
                teacherId = TEACHER_ID,
                studentIds = listOf(STUDENT_ID),
                title = "German Lesson",
                type = LessonType.ONE_ON_ONE,
                scheduledAt = scheduledAt,
                topic = "Conversation practice",
            )
        )
    }.body()

    private suspend fun move(client: HttpClient, token: String, lessonId: String, request: MoveLessonRequest): HttpResponse =
        client.post("/api/v1/lessons/$lessonId/move") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(request)
        }

    private suspend fun getLesson(client: HttpClient, token: String, lessonId: String): LessonResponse =
        client.get("/api/v1/lessons/$lessonId") { bearerAuth(token) }.body()

    private suspend fun movedNotifications(client: HttpClient, token: String, lessonId: String) =
        client.get("/api/v1/notifications") { bearerAuth(token) }
            .body<NotificationPageResponse>().notifications
            .filter { it.type == NotificationType.LESSON_MOVED && it.referenceId == lessonId }

    private fun movedEvents(lessonId: String) = transaction {
        LessonEventTable.selectAll()
            .where {
                (LessonEventTable.lessonId eq UUID.fromString(lessonId)) and
                        (LessonEventTable.eventType eq LessonEventType.LESSON_MOVED)
            }
            .toList()
    }

    @Test
    fun `teacher moves a lesson with new duration and topic`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val lesson = createLesson(client, teacherToken)
        val newTime = OffsetDateTime.parse("2027-04-12T14:00:00+02:00")

        val response = move(client, teacherToken, lesson.id, MoveLessonRequest(newTime, durationMinutes = 90, topic = "Dativ"))

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<MoveLessonResponse>()
        assertEquals(false, body.dryRun)
        assertTrue(body.warnings.isEmpty())
        assertTrue(body.lesson.scheduledAt.isEqual(newTime))

        val stored = getLesson(client, teacherToken, lesson.id)
        assertTrue(stored.scheduledAt.isEqual(newTime))
        assertEquals(90, stored.durationMinutes)
        assertEquals("Dativ", stored.topic)
        assertEquals(LessonStatus.CONFIRMED, stored.status)
    }

    @Test
    fun `admin can move any lesson`() = testApp {
        val client = createJsonClient(this)
        val lesson = createLesson(client, getTeacherToken(client))

        val response = move(client, getAdminToken(client), lesson.id, MoveLessonRequest(OffsetDateTime.parse("2027-04-12T15:00:00+02:00")))

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `move onto another lesson is a conflict`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val lesson = createLesson(client, teacherToken)
        createLesson(client, teacherToken, OffsetDateTime.parse("2027-04-12T14:00:00+02:00"))

        val response = move(client, teacherToken, lesson.id, MoveLessonRequest(OffsetDateTime.parse("2027-04-12T14:30:00+02:00")))

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("AVAILABILITY_OVERLAP", response.body<ProblemDetail>().code)
        assertTrue(getLesson(client, teacherToken, lesson.id).scheduledAt.isEqual(originalTime))
    }

    @Test
    fun `move into the teacher buffer is a conflict`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        transaction { exec("UPDATE users SET booking_buffer_minutes = 15 WHERE id = '$TEACHER_ID'") }
        val lesson = createLesson(client, teacherToken)
        createLesson(client, teacherToken, OffsetDateTime.parse("2027-04-12T14:00:00+02:00"))

        val response = move(client, teacherToken, lesson.id, MoveLessonRequest(OffsetDateTime.parse("2027-04-12T15:05:00+02:00")))

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("AVAILABILITY_BUFFER_CONFLICT", response.body<ProblemDetail>().code)
    }

    @Test
    fun `only confirmed lessons can be moved`() = testApp {
        val client = createJsonClient(this)
        val request = createLesson(client, getStudentToken(client))
        assertEquals(LessonStatus.REQUEST, request.status)

        val response = move(client, getTeacherToken(client), request.id, MoveLessonRequest(OffsetDateTime.parse("2027-04-12T14:00:00+02:00")))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("LESSON_INVALID_STATE", response.body<ProblemDetail>().code)
    }

    @Test
    fun `student cannot move a lesson`() = testApp {
        val client = createJsonClient(this)
        val lesson = createLesson(client, getTeacherToken(client))

        val response = move(client, getStudentToken(client), lesson.id, MoveLessonRequest(OffsetDateTime.parse("2027-04-12T14:00:00+02:00")))

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `move outside working hours and onto a blocked day succeeds with warnings`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        transaction {
            exec(
                "INSERT INTO teacher_weekly_availability (teacher_id, day_of_week, start_time, end_time) " +
                        "SELECT '$TEACHER_ID', d, '09:00', '17:00' FROM generate_series(1, 7) AS d"
            )
            exec(
                "INSERT INTO teacher_availability_exception (teacher_id, type, start_at, end_at) " +
                        "VALUES ('$TEACHER_ID', 'BLOCKED', '2027-04-14T00:00:00+02:00', '2027-04-15T00:00:00+02:00')"
            )
        }
        val lesson = createLesson(client, teacherToken)

        val evening = move(client, teacherToken, lesson.id, MoveLessonRequest(OffsetDateTime.parse("2027-04-12T20:00:00+02:00")))
        assertEquals(HttpStatusCode.OK, evening.status)
        assertEquals(listOf(ScheduleWarning.OUTSIDE_WORKING_HOURS), evening.body<MoveLessonResponse>().warnings)

        val blockedTime = OffsetDateTime.parse("2027-04-14T10:00:00+02:00")
        val blocked = move(client, teacherToken, lesson.id, MoveLessonRequest(blockedTime))
        assertEquals(HttpStatusCode.OK, blocked.status)
        assertEquals(listOf(ScheduleWarning.BLOCKED_DAY), blocked.body<MoveLessonResponse>().warnings)
        assertTrue(getLesson(client, teacherToken, lesson.id).scheduledAt.isEqual(blockedTime))
    }

    @Test
    fun `dry run reports warnings and changes nothing`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)
        transaction {
            exec(
                "INSERT INTO teacher_weekly_availability (teacher_id, day_of_week, start_time, end_time) " +
                        "SELECT '$TEACHER_ID', d, '09:00', '17:00' FROM generate_series(1, 7) AS d"
            )
        }
        val lesson = createLesson(client, teacherToken)

        val response = move(
            client, teacherToken, lesson.id,
            MoveLessonRequest(OffsetDateTime.parse("2027-04-12T20:00:00+02:00"), durationMinutes = 30, dryRun = true),
        )

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<MoveLessonResponse>()
        assertEquals(true, body.dryRun)
        assertEquals(listOf(ScheduleWarning.OUTSIDE_WORKING_HOURS), body.warnings)
        assertTrue(body.lesson.scheduledAt.isEqual(originalTime))

        val stored = getLesson(client, teacherToken, lesson.id)
        assertTrue(stored.scheduledAt.isEqual(originalTime))
        assertEquals(60, stored.durationMinutes)
        assertTrue(movedEvents(lesson.id).isEmpty())
        assertTrue(movedNotifications(client, studentToken, lesson.id).isEmpty())
    }

    @Test
    fun `dry run still reports a clash`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val lesson = createLesson(client, teacherToken)
        createLesson(client, teacherToken, OffsetDateTime.parse("2027-04-12T14:00:00+02:00"))

        val response = move(
            client, teacherToken, lesson.id,
            MoveLessonRequest(OffsetDateTime.parse("2027-04-12T14:00:00+02:00"), dryRun = true),
        )

        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test
    fun `move writes a lesson moved event`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val lesson = createLesson(client, teacherToken)
        val newTime = OffsetDateTime.parse("2027-04-12T14:00:00+02:00")

        move(client, teacherToken, lesson.id, MoveLessonRequest(newTime))

        val event = movedEvents(lesson.id).single()
        assertEquals(TEACHER_ID, event[LessonEventTable.actorId].value.toString())
        assertTrue(event[LessonEventTable.oldScheduledAt]!!.isEqual(originalTime))
        assertTrue(event[LessonEventTable.newScheduledAt]!!.isEqual(newTime))
    }

    @Test
    fun `student is notified with old and new time`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)
        val lesson = createLesson(client, teacherToken)
        val newTime = OffsetDateTime.parse("2027-04-12T14:00:00+02:00")

        move(client, teacherToken, lesson.id, MoveLessonRequest(newTime))

        val notification = movedNotifications(client, studentToken, lesson.id).single()
        assertEquals(TEACHER_ID, notification.actor.id)
        assertTrue(notification.oldScheduledAt!!.isEqual(originalTime))
        assertTrue(notification.newScheduledAt!!.isEqual(newTime))
    }

    @Test
    fun `notify false moves silently`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)
        val lesson = createLesson(client, teacherToken)

        val response = move(client, teacherToken, lesson.id, MoveLessonRequest(OffsetDateTime.parse("2027-04-12T14:00:00+02:00"), notify = false))

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(movedNotifications(client, studentToken, lesson.id).isEmpty())
        assertEquals(1, movedEvents(lesson.id).size)
    }

    @Test
    fun `move resolves a pending reschedule proposal`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)
        val lesson = createLesson(client, teacherToken)
        client.post("/api/v1/lessons/${lesson.id}/reschedule") {
            contentType(ContentType.Application.Json)
            bearerAuth(studentToken)
            setBody(RescheduleLessonRequest(newScheduledAt = OffsetDateTime.parse("2027-04-13T10:00:00+02:00")))
        }
        assertNotNull(getLesson(client, teacherToken, lesson.id).pendingReschedule)

        val response = move(client, teacherToken, lesson.id, MoveLessonRequest(OffsetDateTime.parse("2027-04-12T14:00:00+02:00")))

        assertEquals(HttpStatusCode.OK, response.status)
        assertNull(response.body<MoveLessonResponse>().lesson.pendingReschedule)
        assertNull(getLesson(client, teacherToken, lesson.id).pendingReschedule)
    }
}
