package com.gvart.parleyroom.lesson

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.lesson.transfer.CompleteLessonRequest
import com.gvart.parleyroom.lesson.transfer.CreateLessonRequest
import com.gvart.parleyroom.lesson.transfer.LessonDocumentResponse
import com.gvart.parleyroom.lesson.transfer.LessonPageResponse
import com.gvart.parleyroom.lesson.transfer.LessonResponse
import com.gvart.parleyroom.lesson.transfer.PublicCalendarResponse
import java.util.UUID
import com.gvart.parleyroom.lesson.transfer.ReflectLessonRequest
import com.gvart.parleyroom.lesson.transfer.RescheduleLessonRequest
import com.gvart.parleyroom.lesson.transfer.StartLessonResponse
import com.gvart.parleyroom.lesson.transfer.SyncLessonDocumentRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import com.gvart.parleyroom.lesson.transfer.CancelLessonRequest
import com.gvart.parleyroom.lesson.transfer.PendingRescheduleResponse
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LessonIntegrationTest : IntegrationTest() {

    private suspend fun createLesson(
        client: HttpClient,
        token: String,
        type: LessonType = LessonType.ONE_ON_ONE,
        maxParticipants: Int? = null,
        scheduledAt: String = "2027-04-10T10:00:00+02:00",
        durationMinutes: Int = 60,
        studentIds: List<String> = listOf(STUDENT_ID),
    ): HttpResponse = client.post("/api/v1/lessons") {
        contentType(ContentType.Application.Json)
        bearerAuth(token)
        setBody(
            CreateLessonRequest(
                teacherId = TEACHER_ID,
                studentIds = studentIds,
                title = "German Lesson",
                type = type,
                scheduledAt = OffsetDateTime.parse(scheduledAt),
                durationMinutes = durationMinutes,
                topic = "Conversation practice",
                maxParticipants = maxParticipants,
            )
        )
    }

    // -- Create --

    @Test
    fun `teacher creates a confirmed lesson`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val response = createLesson(client, token)

        assertEquals(HttpStatusCode.Created, response.status)
        val lesson = response.body<LessonResponse>()
        assertEquals(LessonStatus.CONFIRMED, lesson.status)
        assertEquals(TEACHER_ID, lesson.createdBy)
        assertEquals(TEACHER_ID, lesson.teacherId)
        assertEquals(1, lesson.students.size)
        assertEquals(STUDENT_ID, lesson.students[0].id)
        assertEquals("CONFIRMED", lesson.students[0].status)
        assertNull(lesson.startedAt)
        assertNull(lesson.teacherNotes)
        assertNull(lesson.studentNotes)
    }

    @Test
    fun `student creates a lesson as request`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val response = createLesson(client, token)

        assertEquals(HttpStatusCode.Created, response.status)
        val lesson = response.body<LessonResponse>()
        assertEquals(LessonStatus.REQUEST, lesson.status)
        assertEquals(STUDENT_ID, lesson.createdBy)
    }

    @Test
    fun `student cannot create group lessons`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val response = createLesson(client, token, type = LessonType.SPEAKING_CLUB)

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `teacher can create group lessons`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val response = createLesson(client, token, type = LessonType.SPEAKING_CLUB)

        assertEquals(HttpStatusCode.Created, response.status)
        val lesson = response.body<LessonResponse>()
        assertEquals(LessonType.SPEAKING_CLUB, lesson.type)
    }

    @Test
    fun `teacher can create an open group lesson with no students`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val response = createLesson(
            client,
            token,
            type = LessonType.SPEAKING_CLUB,
            studentIds = emptyList(),
        )

        assertEquals(HttpStatusCode.Created, response.status)
        val lesson = response.body<LessonResponse>()
        assertEquals(LessonType.SPEAKING_CLUB, lesson.type)
        assertTrue(lesson.students.isEmpty())
    }

    @Test
    fun `one-on-one with zero students is rejected`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val response = createLesson(client, token, studentIds = emptyList())

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `one-on-one with multiple students is rejected`() = testApp {
        val client = createJsonClient(this)
        val token = getAdminToken(client)

        val response = createLesson(
            client,
            token,
            studentIds = listOf(STUDENT_ID, STUDENT_2_ID),
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `admin can create a group lesson with multiple students`() = testApp {
        val client = createJsonClient(this)
        val token = getAdminToken(client)

        val response = createLesson(
            client,
            token,
            type = LessonType.READING_CLUB,
            studentIds = listOf(STUDENT_ID, STUDENT_2_ID),
        )

        assertEquals(HttpStatusCode.Created, response.status)
        val lesson = response.body<LessonResponse>()
        assertEquals(2, lesson.students.size)
    }

    @Test
    fun `studentIds exceeding maxParticipants is rejected`() = testApp {
        val client = createJsonClient(this)
        val token = getAdminToken(client)

        val response = createLesson(
            client,
            token,
            type = LessonType.SPEAKING_CLUB,
            studentIds = listOf(STUDENT_ID, STUDENT_2_ID),
            maxParticipants = 1,
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `create lesson requires authentication`() = testApp {
        val client = createJsonClient(this)

        val response = client.post("/api/v1/lessons") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateLessonRequest(
                    teacherId = TEACHER_ID,
                    studentIds = listOf(STUDENT_ID),
                    title = "German Lesson",
                    type = LessonType.ONE_ON_ONE,
                    scheduledAt = OffsetDateTime.parse("2027-04-10T10:00:00+02:00"),
                    topic = "Conversation practice",
                )
            )
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // -- Get by ID --

    @Test
    fun `teacher can get their lesson by id`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val created = createLesson(client, token).body<LessonResponse>()

        val response = client.get("/api/v1/lessons/${created.id}") {
            bearerAuth(token)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val lesson = response.body<LessonResponse>()
        assertEquals(created.id, lesson.id)
        assertEquals(1, lesson.students.size)
        assertEquals(STUDENT_ID, lesson.students[0].id)
    }

    @Test
    fun `student can get lesson they participate in`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        val created = createLesson(client, teacherToken).body<LessonResponse>()

        val response = client.get("/api/v1/lessons/${created.id}") {
            bearerAuth(studentToken)
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `non-participant cannot get lesson`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val student2Token = getStudent2Token(client)

        val created = createLesson(client, teacherToken).body<LessonResponse>()

        val response = client.get("/api/v1/lessons/${created.id}") {
            bearerAuth(student2Token)
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `get non-existent lesson returns 404`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val response = client.get("/api/v1/lessons/00000000-0000-0000-0000-000000000099") {
            bearerAuth(token)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // -- Overlap --

    @Test
    fun `cannot create overlapping lesson for same teacher`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val first = createLesson(client, token, scheduledAt = "2027-04-10T10:00:00+02:00", durationMinutes = 60)
        assertEquals(HttpStatusCode.Created, first.status)

        val overlapping = createLesson(client, token, scheduledAt = "2027-04-10T10:30:00+02:00", durationMinutes = 60)
        assertEquals(HttpStatusCode.Conflict, overlapping.status)
    }

    @Test
    fun `can create non-overlapping lessons for same teacher`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val first = createLesson(client, token, scheduledAt = "2027-04-10T10:00:00+02:00", durationMinutes = 60)
        assertEquals(HttpStatusCode.Created, first.status)

        val second = createLesson(client, token, scheduledAt = "2027-04-10T11:00:00+02:00", durationMinutes = 60)
        assertEquals(HttpStatusCode.Created, second.status)
    }

    @Test
    fun `different teachers can have lessons at the same time`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val adminToken = getAdminToken(client)

        val first = createLesson(client, teacherToken, scheduledAt = "2027-04-10T10:00:00+02:00")
        assertEquals(HttpStatusCode.Created, first.status)

        // Admin creates a lesson for themselves (different teacher)
        val response = client.post("/api/v1/lessons") {
            contentType(ContentType.Application.Json)
            bearerAuth(adminToken)
            setBody(
                CreateLessonRequest(
                    teacherId = ADMIN_ID,
                    studentIds = listOf(STUDENT_2_ID),
                    title = "Admin Lesson",
                    type = LessonType.ONE_ON_ONE,
                    scheduledAt = OffsetDateTime.parse("2027-04-10T10:00:00+02:00"),
                    topic = "Same time, different teacher",
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status)
    }

    @Test
    fun `teacher can accept a student request`() = testApp {
        val client = createJsonClient(this)
        val studentToken = getStudentToken(client)
        val teacherToken = getTeacherToken(client)

        val lesson = createLesson(client, studentToken).body<LessonResponse>()
        assertEquals(LessonStatus.REQUEST, lesson.status)

        val response = client.post("/api/v1/lessons/${lesson.id}/accept") {
            bearerAuth(teacherToken)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val accepted = response.body<LessonResponse>()
        assertEquals(LessonStatus.CONFIRMED, accepted.status)
    }

    @Test
    fun `student cannot accept a lesson request`() = testApp {
        val client = createJsonClient(this)
        val studentToken = getStudentToken(client)

        val lesson = createLesson(client, studentToken).body<LessonResponse>()

        val response = client.post("/api/v1/lessons/${lesson.id}/accept") {
            bearerAuth(studentToken)
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `cannot accept already confirmed lesson`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()
        assertEquals(LessonStatus.CONFIRMED, lesson.status)

        val response = client.post("/api/v1/lessons/${lesson.id}/accept") {
            bearerAuth(teacherToken)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `teacher sees their lessons`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        createLesson(client, teacherToken)
        createLesson(client, teacherToken, type = LessonType.SPEAKING_CLUB, scheduledAt = "2027-04-10T12:00:00+02:00")

        val response = client.get("/api/v1/lessons") {
            bearerAuth(teacherToken)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val lessons = response.body<LessonPageResponse>().lessons
        assertEquals(2, lessons.size)
        assertTrue(lessons.all { it.teacherId == TEACHER_ID })
    }

    @Test
    fun `student sees lessons they participate in`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)
        val student2Token = getStudent2Token(client)

        // Teacher creates confirmed lesson with student
        createLesson(client, teacherToken)
        // Student creates request lesson (also a participant) at a different time
        createLesson(client, studentToken, scheduledAt = "2027-04-10T12:00:00+02:00")

        val response = client.get("/api/v1/lessons") {
            bearerAuth(studentToken)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val lessons = response.body<LessonPageResponse>().lessons
        assertEquals(2, lessons.size)

        // Student2 has no lessons
        val student2Lessons = client.get("/api/v1/lessons") {
            bearerAuth(student2Token)
        }.body<LessonPageResponse>().lessons
        assertEquals(0, student2Lessons.size)
    }

    @Test
    fun `admin sees all lessons`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)
        val adminToken = getAdminToken(client)

        createLesson(client, teacherToken)
        createLesson(client, studentToken, scheduledAt = "2027-04-10T12:00:00+02:00")

        val response = client.get("/api/v1/lessons") {
            bearerAuth(adminToken)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val lessons = response.body<LessonPageResponse>().lessons
        assertEquals(2, lessons.size)
    }

    @Test
    fun `filter lessons by date range`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        // Lesson on April 10
        createLesson(client, teacherToken)

        // Lesson on April 20
        client.post("/api/v1/lessons") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(
                CreateLessonRequest(
                    teacherId = TEACHER_ID,
                    studentIds = listOf(STUDENT_ID),
                    title = "Later Lesson",
                    type = LessonType.ONE_ON_ONE,
                    scheduledAt = OffsetDateTime.parse("2027-04-20T10:00:00+02:00"),
                    topic = "Grammar review",
                )
            )
        }

        // Only April 10 lesson
        val filtered = client.get("/api/v1/lessons?from=2027-04-09T00:00:00%2B02:00&to=2027-04-11T00:00:00%2B02:00") {
            bearerAuth(teacherToken)
        }
        assertEquals(HttpStatusCode.OK, filtered.status)
        assertEquals(1, filtered.body<LessonPageResponse>().lessons.size)

        // Both lessons
        val all = client.get("/api/v1/lessons?from=2027-04-01T00:00:00%2B02:00&to=2027-04-30T00:00:00%2B02:00") {
            bearerAuth(teacherToken)
        }
        assertEquals(2, all.body<LessonPageResponse>().lessons.size)

        // No filter returns all
        val noFilter = client.get("/api/v1/lessons") {
            bearerAuth(teacherToken)
        }
        assertEquals(2, noFilter.body<LessonPageResponse>().lessons.size)
    }

    @Test
    fun `student can request to join a group lesson`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val student2Token = getStudent2Token(client)

        val lesson = createLesson(client, teacherToken, type = LessonType.SPEAKING_CLUB).body<LessonResponse>()

        val response = client.post("/api/v1/lessons/${lesson.id}/join") {
            bearerAuth(student2Token)
        }

        assertEquals(HttpStatusCode.Created, response.status)
    }

    @Test
    fun `cannot join a one-on-one lesson`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val student2Token = getStudent2Token(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()

        val response = client.post("/api/v1/lessons/${lesson.id}/join") {
            bearerAuth(student2Token)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `cannot join if already a participant`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        val lesson = createLesson(client, teacherToken, type = LessonType.SPEAKING_CLUB).body<LessonResponse>()

        // Student is already in the lesson from creation
        val response = client.post("/api/v1/lessons/${lesson.id}/join") {
            bearerAuth(studentToken)
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test
    fun `cannot join if request already pending`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val student2Token = getStudent2Token(client)

        val lesson = createLesson(client, teacherToken, type = LessonType.SPEAKING_CLUB).body<LessonResponse>()

        client.post("/api/v1/lessons/${lesson.id}/join") {
            bearerAuth(student2Token)
        }

        val response = client.post("/api/v1/lessons/${lesson.id}/join") {
            bearerAuth(student2Token)
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test
    fun `cannot join a full lesson`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val student2Token = getStudent2Token(client)

        // maxParticipants=1, student already confirmed
        val lesson = createLesson(client, teacherToken, type = LessonType.SPEAKING_CLUB, maxParticipants = 1)
            .body<LessonResponse>()

        val response = client.post("/api/v1/lessons/${lesson.id}/join") {
            bearerAuth(student2Token)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `teacher can accept join request`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val student2Token = getStudent2Token(client)

        val lesson = createLesson(client, teacherToken, type = LessonType.SPEAKING_CLUB).body<LessonResponse>()

        client.post("/api/v1/lessons/${lesson.id}/join") {
            bearerAuth(student2Token)
        }

        val response = client.post("/api/v1/lessons/${lesson.id}/participants/$STUDENT_2_ID/accept") {
            bearerAuth(teacherToken)
        }

        assertEquals(HttpStatusCode.OK, response.status)

        // Student2 should now see the lesson
        val lessons = client.get("/api/v1/lessons") {
            bearerAuth(student2Token)
        }.body<LessonPageResponse>().lessons

        assertEquals(1, lessons.size)

        // Verify both students appear in the lesson's student list
        val updated = client.get("/api/v1/lessons/${lesson.id}") {
            bearerAuth(teacherToken)
        }.body<LessonResponse>()
        assertEquals(2, updated.students.size)
        assertTrue(updated.students.any { it.id == STUDENT_ID && it.status == "CONFIRMED" })
        assertTrue(updated.students.any { it.id == STUDENT_2_ID && it.status == "CONFIRMED" })
    }

    @Test
    fun `teacher can reject join request`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val student2Token = getStudent2Token(client)

        val lesson = createLesson(client, teacherToken, type = LessonType.SPEAKING_CLUB).body<LessonResponse>()

        client.post("/api/v1/lessons/${lesson.id}/join") {
            bearerAuth(student2Token)
        }

        val response = client.post("/api/v1/lessons/${lesson.id}/participants/$STUDENT_2_ID/reject") {
            bearerAuth(teacherToken)
        }

        assertEquals(HttpStatusCode.OK, response.status)

        // Student2 should not see the lesson
        val lessons = client.get("/api/v1/lessons") {
            bearerAuth(student2Token)
        }.body<LessonPageResponse>().lessons

        assertEquals(0, lessons.size)
    }

    @Test
    fun `student cannot accept join requests`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)
        val student2Token = getStudent2Token(client)

        val lesson = createLesson(client, teacherToken, type = LessonType.SPEAKING_CLUB).body<LessonResponse>()

        client.post("/api/v1/lessons/${lesson.id}/join") {
            bearerAuth(student2Token)
        }

        val response = client.post("/api/v1/lessons/${lesson.id}/participants/$STUDENT_2_ID/accept") {
            bearerAuth(studentToken)
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `student can re-request after rejection`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val student2Token = getStudent2Token(client)

        val lesson = createLesson(client, teacherToken, type = LessonType.SPEAKING_CLUB).body<LessonResponse>()

        // Request, reject, re-request
        client.post("/api/v1/lessons/${lesson.id}/join") {
            bearerAuth(student2Token)
        }
        client.post("/api/v1/lessons/${lesson.id}/participants/$STUDENT_2_ID/reject") {
            bearerAuth(teacherToken)
        }

        val response = client.post("/api/v1/lessons/${lesson.id}/join") {
            bearerAuth(student2Token)
        }

        assertEquals(HttpStatusCode.Created, response.status)
    }

    // -- Reschedule --

    @Test
    fun `teacher can request reschedule`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()

        val response = client.post("/api/v1/lessons/${lesson.id}/reschedule") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(RescheduleLessonRequest(newScheduledAt = OffsetDateTime.parse("2027-04-12T14:00:00+02:00"), note = "Conflict"))
        }

        assertEquals(HttpStatusCode.Created, response.status)
    }

    @Test
    fun `student can request reschedule`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()

        val response = client.post("/api/v1/lessons/${lesson.id}/reschedule") {
            contentType(ContentType.Application.Json)
            bearerAuth(studentToken)
            setBody(RescheduleLessonRequest(newScheduledAt = OffsetDateTime.parse("2027-04-12T14:00:00+02:00")))
        }

        assertEquals(HttpStatusCode.Created, response.status)
    }

    @Test
    fun `cannot request reschedule when one is pending`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()

        client.post("/api/v1/lessons/${lesson.id}/reschedule") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(RescheduleLessonRequest(newScheduledAt = OffsetDateTime.parse("2027-04-12T14:00:00+02:00")))
        }

        val response = client.post("/api/v1/lessons/${lesson.id}/reschedule") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(RescheduleLessonRequest(newScheduledAt = OffsetDateTime.parse("2027-04-13T14:00:00+02:00")))
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test
    fun `cannot reschedule unconfirmed lesson`() = testApp {
        val client = createJsonClient(this)
        val studentToken = getStudentToken(client)

        val lesson = createLesson(client, studentToken).body<LessonResponse>()
        assertEquals(LessonStatus.REQUEST, lesson.status)

        val response = client.post("/api/v1/lessons/${lesson.id}/reschedule") {
            contentType(ContentType.Application.Json)
            bearerAuth(studentToken)
            setBody(RescheduleLessonRequest(newScheduledAt = OffsetDateTime.parse("2027-04-12T14:00:00+02:00")))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `student can accept teacher reschedule`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()

        client.post("/api/v1/lessons/${lesson.id}/reschedule") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(RescheduleLessonRequest(newScheduledAt = OffsetDateTime.parse("2027-04-12T14:00:00+02:00")))
        }

        val response = client.post("/api/v1/lessons/${lesson.id}/reschedule/accept") {
            bearerAuth(studentToken)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val updated = response.body<LessonResponse>()
        assertTrue(updated.scheduledAt.toString().contains("2027-04-12"))
    }

    @Test
    fun `teacher can accept student reschedule`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()

        client.post("/api/v1/lessons/${lesson.id}/reschedule") {
            contentType(ContentType.Application.Json)
            bearerAuth(studentToken)
            setBody(RescheduleLessonRequest(newScheduledAt = OffsetDateTime.parse("2027-04-12T14:00:00+02:00")))
        }

        val response = client.post("/api/v1/lessons/${lesson.id}/reschedule/accept") {
            bearerAuth(teacherToken)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val updated = response.body<LessonResponse>()
        assertTrue(updated.scheduledAt.toString().contains("2027-04-12"))
    }

    @Test
    fun `cannot accept own reschedule request`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()

        client.post("/api/v1/lessons/${lesson.id}/reschedule") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(RescheduleLessonRequest(newScheduledAt = OffsetDateTime.parse("2027-04-12T14:00:00+02:00")))
        }

        val response = client.post("/api/v1/lessons/${lesson.id}/reschedule/accept") {
            bearerAuth(teacherToken)
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `teacher can reject student reschedule`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()

        client.post("/api/v1/lessons/${lesson.id}/reschedule") {
            contentType(ContentType.Application.Json)
            bearerAuth(studentToken)
            setBody(RescheduleLessonRequest(newScheduledAt = OffsetDateTime.parse("2027-04-12T14:00:00+02:00")))
        }

        val response = client.post("/api/v1/lessons/${lesson.id}/reschedule/reject") {
            bearerAuth(teacherToken)
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `can reschedule again after rejection`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()

        client.post("/api/v1/lessons/${lesson.id}/reschedule") {
            contentType(ContentType.Application.Json)
            bearerAuth(studentToken)
            setBody(RescheduleLessonRequest(newScheduledAt = OffsetDateTime.parse("2027-04-12T14:00:00+02:00")))
        }

        client.post("/api/v1/lessons/${lesson.id}/reschedule/reject") {
            bearerAuth(teacherToken)
        }

        val response = client.post("/api/v1/lessons/${lesson.id}/reschedule") {
            contentType(ContentType.Application.Json)
            bearerAuth(studentToken)
            setBody(RescheduleLessonRequest(newScheduledAt = OffsetDateTime.parse("2027-04-13T14:00:00+02:00")))
        }

        assertEquals(HttpStatusCode.Created, response.status)
    }

    @Test
    fun `cannot reject own reschedule request`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()

        client.post("/api/v1/lessons/${lesson.id}/reschedule") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(RescheduleLessonRequest(newScheduledAt = OffsetDateTime.parse("2027-04-12T14:00:00+02:00")))
        }

        val response = client.post("/api/v1/lessons/${lesson.id}/reschedule/reject") {
            bearerAuth(teacherToken)
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // -- Start --

    @Test
    fun `teacher can start a confirmed lesson`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()

        val response = client.post("/api/v1/lessons/${lesson.id}/start") {
            bearerAuth(teacherToken)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val start = response.body<StartLessonResponse>()
        assertEquals(lesson.id, start.document.lessonId)
        assertEquals("lesson-${lesson.id}", start.videoRoom.roomName)
        assertTrue(start.videoRoom.accessToken.isNotBlank())
    }

    @Test
    fun `student cannot start a lesson`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()

        val response = client.post("/api/v1/lessons/${lesson.id}/start") {
            bearerAuth(studentToken)
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `cannot start unconfirmed lesson`() = testApp {
        val client = createJsonClient(this)
        val studentToken = getStudentToken(client)
        val teacherToken = getTeacherToken(client)

        val lesson = createLesson(client, studentToken).body<LessonResponse>()
        assertEquals(LessonStatus.REQUEST, lesson.status)

        val response = client.post("/api/v1/lessons/${lesson.id}/start") {
            bearerAuth(teacherToken)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `cannot start already started lesson`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()

        client.post("/api/v1/lessons/${lesson.id}/start") {
            bearerAuth(teacherToken)
        }

        val response = client.post("/api/v1/lessons/${lesson.id}/start") {
            bearerAuth(teacherToken)
        }

        // Status is now IN_PROGRESS, so it fails the CONFIRMED check
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // -- Sync --

    @Test
    fun `teacher can sync teacher notes`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()
        client.post("/api/v1/lessons/${lesson.id}/start") { bearerAuth(teacherToken) }

        val response = client.put("/api/v1/lessons/${lesson.id}/sync") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(SyncLessonDocumentRequest(notes = "Great progress today"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val doc = response.body<LessonDocumentResponse>()
        assertEquals("Great progress today", doc.teacherNotes)
    }

    @Test
    fun `student can sync student notes`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()
        client.post("/api/v1/lessons/${lesson.id}/start") { bearerAuth(teacherToken) }

        val response = client.put("/api/v1/lessons/${lesson.id}/sync") {
            contentType(ContentType.Application.Json)
            bearerAuth(studentToken)
            setBody(SyncLessonDocumentRequest(notes = "Learned new vocabulary"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val doc = response.body<LessonDocumentResponse>()
        assertEquals("Learned new vocabulary", doc.studentNotes)
    }

    @Test
    fun `cannot sync before lesson is started`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()

        val response = client.put("/api/v1/lessons/${lesson.id}/sync") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(SyncLessonDocumentRequest(notes = "Notes"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `non-participant cannot sync`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val student2Token = getStudent2Token(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()
        client.post("/api/v1/lessons/${lesson.id}/start") { bearerAuth(teacherToken) }

        val response = client.put("/api/v1/lessons/${lesson.id}/sync") {
            contentType(ContentType.Application.Json)
            bearerAuth(student2Token)
            setBody(SyncLessonDocumentRequest(notes = "Notes"))
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // -- Complete --

    @Test
    fun `teacher can complete a started lesson`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()
        client.post("/api/v1/lessons/${lesson.id}/start") { bearerAuth(teacherToken) }

        val response = client.post("/api/v1/lessons/${lesson.id}/complete") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(CompleteLessonRequest(
                teacherNotes = "Final notes",
                teacherWentWell = "Pronunciation improved",
                teacherWorkingOn = "Grammar articles",
            ))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val doc = response.body<LessonDocumentResponse>()
        assertEquals("Final notes", doc.teacherNotes)
        assertEquals("Pronunciation improved", doc.teacherWentWell)
        assertEquals("Grammar articles", doc.teacherWorkingOn)
    }

    @Test
    fun `student cannot complete a lesson`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()
        client.post("/api/v1/lessons/${lesson.id}/start") { bearerAuth(teacherToken) }

        val response = client.post("/api/v1/lessons/${lesson.id}/complete") {
            contentType(ContentType.Application.Json)
            bearerAuth(studentToken)
            setBody(CompleteLessonRequest())
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `cannot complete lesson that has not started`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()

        val response = client.post("/api/v1/lessons/${lesson.id}/complete") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(CompleteLessonRequest(teacherNotes = "Notes"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `cannot complete already completed lesson`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()
        client.post("/api/v1/lessons/${lesson.id}/start") { bearerAuth(teacherToken) }
        client.post("/api/v1/lessons/${lesson.id}/complete") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(CompleteLessonRequest(teacherNotes = "Done"))
        }

        val response = client.post("/api/v1/lessons/${lesson.id}/complete") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(CompleteLessonRequest(teacherNotes = "Done again"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `full lesson lifecycle`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        // Create
        val lesson = createLesson(client, teacherToken).body<LessonResponse>()
        assertEquals(LessonStatus.CONFIRMED, lesson.status)
        assertEquals(1, lesson.students.size)
        assertNull(lesson.startedAt)
        assertNull(lesson.teacherNotes)

        // Start
        val startDoc = client.post("/api/v1/lessons/${lesson.id}/start") {
            bearerAuth(teacherToken)
        }.body<StartLessonResponse>().document
        assertEquals(lesson.id, startDoc.lessonId)

        // Verify startedAt is set and status is IN_PROGRESS via GET
        val afterStart = client.get("/api/v1/lessons/${lesson.id}") {
            bearerAuth(teacherToken)
        }.body<LessonResponse>()
        assertNotNull(afterStart.startedAt)
        assertEquals(LessonStatus.IN_PROGRESS, afterStart.status)

        // Teacher syncs notes
        val teacherSync = client.put("/api/v1/lessons/${lesson.id}/sync") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(SyncLessonDocumentRequest(notes = "Working on articles"))
        }.body<LessonDocumentResponse>()
        assertEquals("Working on articles", teacherSync.teacherNotes)

        // Student syncs notes
        val studentSync = client.put("/api/v1/lessons/${lesson.id}/sync") {
            contentType(ContentType.Application.Json)
            bearerAuth(studentToken)
            setBody(SyncLessonDocumentRequest(notes = "Learning der/die/das"))
        }.body<LessonDocumentResponse>()
        assertEquals("Learning der/die/das", studentSync.studentNotes)
        assertEquals("Working on articles", studentSync.teacherNotes) // teacher notes preserved

        // Verify doc fields appear in LessonResponse
        val midLesson = client.get("/api/v1/lessons/${lesson.id}") {
            bearerAuth(teacherToken)
        }.body<LessonResponse>()
        assertEquals("Working on articles", midLesson.teacherNotes)
        assertEquals("Learning der/die/das", midLesson.studentNotes)

        // Teacher completes
        val complete = client.post("/api/v1/lessons/${lesson.id}/complete") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(CompleteLessonRequest(
                teacherNotes = "Final teacher notes",
                teacherWentWell = "Student engaged well",
                teacherWorkingOn = "Article genders",
            ))
        }.body<LessonDocumentResponse>()
        assertEquals("Final teacher notes", complete.teacherNotes)
        assertEquals("Student engaged well", complete.teacherWentWell)
        assertEquals("Article genders", complete.teacherWorkingOn)
        assertEquals("Learning der/die/das", complete.studentNotes) // student notes preserved

        // Student reflects after completion
        val reflect = client.post("/api/v1/lessons/${lesson.id}/reflect") {
            contentType(ContentType.Application.Json)
            bearerAuth(studentToken)
            setBody(ReflectLessonRequest(
                studentReflection = "I feel more confident",
                studentHardToday = "Dative case",
            ))
        }.body<LessonDocumentResponse>()
        assertEquals("I feel more confident", reflect.studentReflection)
        assertEquals("Dative case", reflect.studentHardToday)
        assertEquals("Final teacher notes", reflect.teacherNotes) // teacher notes preserved

        // Final state via GET includes all doc fields
        val finalLesson = client.get("/api/v1/lessons/${lesson.id}") {
            bearerAuth(teacherToken)
        }.body<LessonResponse>()
        assertEquals(LessonStatus.COMPLETED, finalLesson.status)
        assertNotNull(finalLesson.startedAt)
        assertEquals("Final teacher notes", finalLesson.teacherNotes)
        assertEquals("Learning der/die/das", finalLesson.studentNotes)
        assertEquals("Student engaged well", finalLesson.teacherWentWell)
        assertEquals("Article genders", finalLesson.teacherWorkingOn)
        assertEquals("I feel more confident", finalLesson.studentReflection)
        assertEquals("Dative case", finalLesson.studentHardToday)
    }

    // -- Reflect --

    @Test
    fun `student can reflect on a started lesson`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()
        client.post("/api/v1/lessons/${lesson.id}/start") { bearerAuth(teacherToken) }

        val response = client.post("/api/v1/lessons/${lesson.id}/reflect") {
            contentType(ContentType.Application.Json)
            bearerAuth(studentToken)
            setBody(ReflectLessonRequest(
                studentReflection = "Great session",
                studentHardToday = "Adjective endings",
            ))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val doc = response.body<LessonDocumentResponse>()
        assertEquals("Great session", doc.studentReflection)
        assertEquals("Adjective endings", doc.studentHardToday)
    }

    @Test
    fun `teacher cannot reflect`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()
        client.post("/api/v1/lessons/${lesson.id}/start") { bearerAuth(teacherToken) }

        val response = client.post("/api/v1/lessons/${lesson.id}/reflect") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(ReflectLessonRequest(studentReflection = "Notes"))
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `cannot reflect before lesson is started`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()

        val response = client.post("/api/v1/lessons/${lesson.id}/reflect") {
            contentType(ContentType.Application.Json)
            bearerAuth(studentToken)
            setBody(ReflectLessonRequest(studentReflection = "Notes"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `student can reflect after lesson is completed`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()
        client.post("/api/v1/lessons/${lesson.id}/start") { bearerAuth(teacherToken) }
        client.post("/api/v1/lessons/${lesson.id}/complete") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(CompleteLessonRequest(teacherNotes = "Done"))
        }

        val response = client.post("/api/v1/lessons/${lesson.id}/reflect") {
            contentType(ContentType.Application.Json)
            bearerAuth(studentToken)
            setBody(ReflectLessonRequest(
                studentReflection = "Learned a lot",
                studentHardToday = "Passive voice",
            ))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val doc = response.body<LessonDocumentResponse>()
        assertEquals("Learned a lot", doc.studentReflection)
        assertEquals("Passive voice", doc.studentHardToday)
    }

    // -- Cancel --

    @Test
    fun `teacher can cancel a confirmed lesson`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()

        val response = client.post("/api/v1/lessons/${lesson.id}/cancel") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(CancelLessonRequest(reason = "Schedule conflict"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val cancelled = response.body<LessonResponse>()
        assertEquals(LessonStatus.CANCELLED, cancelled.status)
    }

    @Test
    fun `student can cancel a lesson they participate in`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()

        val response = client.post("/api/v1/lessons/${lesson.id}/cancel") {
            contentType(ContentType.Application.Json)
            bearerAuth(studentToken)
            setBody(CancelLessonRequest())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val cancelled = response.body<LessonResponse>()
        assertEquals(LessonStatus.CANCELLED, cancelled.status)
    }

    @Test
    fun `can cancel a request lesson`() = testApp {
        val client = createJsonClient(this)
        val studentToken = getStudentToken(client)

        val lesson = createLesson(client, studentToken).body<LessonResponse>()
        assertEquals(LessonStatus.REQUEST, lesson.status)

        val response = client.post("/api/v1/lessons/${lesson.id}/cancel") {
            contentType(ContentType.Application.Json)
            bearerAuth(studentToken)
            setBody(CancelLessonRequest())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val cancelled = response.body<LessonResponse>()
        assertEquals(LessonStatus.CANCELLED, cancelled.status)
    }

    @Test
    fun `can cancel an in-progress lesson`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()
        client.post("/api/v1/lessons/${lesson.id}/start") { bearerAuth(teacherToken) }

        val response = client.post("/api/v1/lessons/${lesson.id}/cancel") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(CancelLessonRequest(reason = "Emergency"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val cancelled = response.body<LessonResponse>()
        assertEquals(LessonStatus.CANCELLED, cancelled.status)
    }

    @Test
    fun `cannot cancel a completed lesson`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()
        client.post("/api/v1/lessons/${lesson.id}/start") { bearerAuth(teacherToken) }
        client.post("/api/v1/lessons/${lesson.id}/complete") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(CompleteLessonRequest(teacherNotes = "Done"))
        }

        val response = client.post("/api/v1/lessons/${lesson.id}/cancel") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(CancelLessonRequest())
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `cannot cancel an already cancelled lesson`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()
        client.post("/api/v1/lessons/${lesson.id}/cancel") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(CancelLessonRequest())
        }

        val response = client.post("/api/v1/lessons/${lesson.id}/cancel") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(CancelLessonRequest())
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `non-participant cannot cancel a lesson`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val student2Token = getStudent2Token(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()

        val response = client.post("/api/v1/lessons/${lesson.id}/cancel") {
            contentType(ContentType.Application.Json)
            bearerAuth(student2Token)
            setBody(CancelLessonRequest())
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `cancel resolves pending reschedule`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()

        // Create a pending reschedule
        client.post("/api/v1/lessons/${lesson.id}/reschedule") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(RescheduleLessonRequest(newScheduledAt = OffsetDateTime.parse("2027-04-12T14:00:00+02:00")))
        }

        // Verify reschedule is visible
        val withReschedule = client.get("/api/v1/lessons/${lesson.id}") {
            bearerAuth(teacherToken)
        }.body<LessonResponse>()
        assertNotNull(withReschedule.pendingReschedule)

        // Cancel the lesson
        val cancelled = client.post("/api/v1/lessons/${lesson.id}/cancel") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(CancelLessonRequest())
        }.body<LessonResponse>()

        assertEquals(LessonStatus.CANCELLED, cancelled.status)
        assertNull(cancelled.pendingReschedule)
    }

    // -- IN_PROGRESS status --

    @Test
    fun `starting a lesson sets status to IN_PROGRESS`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()
        assertEquals(LessonStatus.CONFIRMED, lesson.status)

        client.post("/api/v1/lessons/${lesson.id}/start") { bearerAuth(teacherToken) }

        val started = client.get("/api/v1/lessons/${lesson.id}") {
            bearerAuth(teacherToken)
        }.body<LessonResponse>()

        assertEquals(LessonStatus.IN_PROGRESS, started.status)
        assertNotNull(started.startedAt)
    }

    @Test
    fun `cannot sync document for completed lesson`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()
        client.post("/api/v1/lessons/${lesson.id}/start") { bearerAuth(teacherToken) }
        client.post("/api/v1/lessons/${lesson.id}/complete") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(CompleteLessonRequest(teacherNotes = "Done"))
        }

        val response = client.put("/api/v1/lessons/${lesson.id}/sync") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(SyncLessonDocumentRequest(notes = "More notes"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // -- Reschedule visibility --

    @Test
    fun `pending reschedule appears in lesson response`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()
        assertNull(lesson.pendingReschedule)

        client.post("/api/v1/lessons/${lesson.id}/reschedule") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(RescheduleLessonRequest(newScheduledAt = OffsetDateTime.parse("2027-04-12T14:00:00+02:00"), note = "Conflict"))
        }

        val withReschedule = client.get("/api/v1/lessons/${lesson.id}") {
            bearerAuth(teacherToken)
        }.body<LessonResponse>()

        assertNotNull(withReschedule.pendingReschedule)
        assertTrue(withReschedule.pendingReschedule!!.newScheduledAt.toString().contains("2027-04-12"))
        assertEquals("Conflict", withReschedule.pendingReschedule!!.note)
        assertEquals(TEACHER_ID, withReschedule.pendingReschedule!!.requestedBy)
    }

    @Test
    fun `accepted reschedule clears pending reschedule`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()

        client.post("/api/v1/lessons/${lesson.id}/reschedule") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(RescheduleLessonRequest(newScheduledAt = OffsetDateTime.parse("2027-04-12T14:00:00+02:00")))
        }

        client.post("/api/v1/lessons/${lesson.id}/reschedule/accept") {
            bearerAuth(studentToken)
        }

        val afterAccept = client.get("/api/v1/lessons/${lesson.id}") {
            bearerAuth(teacherToken)
        }.body<LessonResponse>()

        assertNull(afterAccept.pendingReschedule)
        assertTrue(afterAccept.scheduledAt.toString().contains("2027-04-12"))
    }

    @Test
    fun `rejected reschedule clears pending reschedule`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()

        client.post("/api/v1/lessons/${lesson.id}/reschedule") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(RescheduleLessonRequest(newScheduledAt = OffsetDateTime.parse("2027-04-12T14:00:00+02:00")))
        }

        client.post("/api/v1/lessons/${lesson.id}/reschedule/reject") {
            bearerAuth(studentToken)
        }

        val afterReject = client.get("/api/v1/lessons/${lesson.id}") {
            bearerAuth(teacherToken)
        }.body<LessonResponse>()

        assertNull(afterReject.pendingReschedule)
    }

    @Test
    fun `create lesson with past date fails validation`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val response = createLesson(client, token, scheduledAt = "2020-01-01T10:00:00+02:00")

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `create lesson with invalid date format fails validation`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val response = client.post("/api/v1/lessons") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(
                """
                {"teacherId":"$TEACHER_ID","studentIds":["$STUDENT_ID"],"title":"Bad","type":"ONE_ON_ONE","scheduledAt":"not-a-date","topic":"x"}
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `reflect with both fields null fails validation`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()
        client.post("/api/v1/lessons/${lesson.id}/start") { bearerAuth(teacherToken) }

        val response = client.post("/api/v1/lessons/${lesson.id}/reflect") {
            contentType(ContentType.Application.Json)
            bearerAuth(studentToken)
            setBody(ReflectLessonRequest())
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `reflect with only studentReflection succeeds`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()
        client.post("/api/v1/lessons/${lesson.id}/start") { bearerAuth(teacherToken) }

        val response = client.post("/api/v1/lessons/${lesson.id}/reflect") {
            contentType(ContentType.Application.Json)
            bearerAuth(studentToken)
            setBody(ReflectLessonRequest(studentReflection = "Good session"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `reflect with only studentHardToday succeeds`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()
        client.post("/api/v1/lessons/${lesson.id}/start") { bearerAuth(teacherToken) }

        val response = client.post("/api/v1/lessons/${lesson.id}/reflect") {
            contentType(ContentType.Application.Json)
            bearerAuth(studentToken)
            setBody(ReflectLessonRequest(studentHardToday = "Dative case"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `lesson list pagination returns slice and total`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        listOf(
            "2027-04-10T10:00:00+02:00",
            "2027-04-11T10:00:00+02:00",
            "2027-04-12T10:00:00+02:00",
        ).forEach { at -> createLesson(client, token, scheduledAt = at) }

        val page = client.get("/api/v1/lessons?page=2&pageSize=2") {
            bearerAuth(token)
        }.body<LessonPageResponse>()

        assertEquals(3, page.total)
        assertEquals(2, page.page)
        assertEquals(2, page.pageSize)
        assertEquals(1, page.lessons.size)
    }

    // -- Student calendar discovery & scrubbing --

    @Test
    fun `student sees teacher's open club lesson in list`() = testApp {
        val client = createJsonClient(this)

        val teacherToken = getTeacherToken(client)
        createLesson(
            client,
            teacherToken,
            type = LessonType.SPEAKING_CLUB,
            studentIds = emptyList(),
        ).also { assertEquals(HttpStatusCode.Created, it.status) }

        val page = client.get("/api/v1/lessons") {
            bearerAuth(getStudentToken(client))
        }.body<LessonPageResponse>()

        val club = page.lessons.single { it.type == LessonType.SPEAKING_CLUB }
        assertEquals("German Lesson", club.title)
        assertEquals("Conversation practice", club.topic)
        assertTrue(club.students.isEmpty())
    }

    @Test
    fun `student sees another students 1-on-1 as a scrubbed busy block`() = testApp {
        val client = createJsonClient(this)

        // Admin creates a 1:1 between teacher and student2, bypassing the
        // teacher-student relationship check.
        createLesson(
            client,
            getAdminToken(client),
            type = LessonType.ONE_ON_ONE,
            studentIds = listOf(STUDENT_2_ID),
        ).also { assertEquals(HttpStatusCode.Created, it.status) }

        val page = client.get("/api/v1/lessons") {
            bearerAuth(getStudentToken(client))
        }.body<LessonPageResponse>()

        val busy = page.lessons.single { it.type == LessonType.ONE_ON_ONE }
        // Lesson still surfaces so the student knows the teacher is busy,
        // but every identifying field is scrubbed.
        assertEquals("", busy.title)
        assertEquals("", busy.topic)
        assertTrue(busy.students.isEmpty())
        assertNull(busy.level)
        assertNull(busy.teacherNotes)
        assertNull(busy.studentNotes)
    }

    @Test
    fun `student's own 1-on-1 lesson keeps full data`() = testApp {
        val client = createJsonClient(this)

        createLesson(client, getTeacherToken(client))
            .also { assertEquals(HttpStatusCode.Created, it.status) }

        val page = client.get("/api/v1/lessons") {
            bearerAuth(getStudentToken(client))
        }.body<LessonPageResponse>()

        val mine = page.lessons.single { it.type == LessonType.ONE_ON_ONE }
        assertEquals("German Lesson", mine.title)
        assertEquals("Conversation practice", mine.topic)
        assertEquals(1, mine.students.size)
        assertEquals(STUDENT_ID, mine.students[0].id)
    }

    // -- Public calendar --

    @Test
    fun `public teacher calendar returns scrubbed lessons without auth`() = testApp {
        val client = createJsonClient(this)

        createLesson(
            client,
            getTeacherToken(client),
            type = LessonType.SPEAKING_CLUB,
            studentIds = emptyList(),
            scheduledAt = "2027-04-10T10:00:00+02:00",
        ).also { assertEquals(HttpStatusCode.Created, it.status) }

        createLesson(
            client,
            getAdminToken(client),
            type = LessonType.ONE_ON_ONE,
            studentIds = listOf(STUDENT_ID),
            scheduledAt = "2027-04-10T14:00:00+02:00",
        ).also { assertEquals(HttpStatusCode.Created, it.status) }

        val response = client.get("/api/v1/public/teachers/$TEACHER_ID/calendar")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.body<PublicCalendarResponse>()
        assertEquals("Test", body.teacher.firstName)
        assertEquals("Teacher", body.teacher.lastName)
        assertEquals(2, body.lessons.size)

        val club = body.lessons.single { it.type == LessonType.SPEAKING_CLUB }
        assertEquals("German Lesson", club.title)
        assertEquals("Conversation practice", club.topic)

        val oneOnOne = body.lessons.single { it.type == LessonType.ONE_ON_ONE }
        assertNull(oneOnOne.title)
        assertNull(oneOnOne.topic)
        assertEquals(1, oneOnOne.participantCount)
    }

    @Test
    fun `public calendar returns 404 for unknown teacher`() = testApp {
        val client = createJsonClient(this)
        val response = client.get("/api/v1/public/teachers/${UUID.randomUUID()}/calendar")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `public calendar returns 404 when id belongs to a non-teacher`() = testApp {
        val client = createJsonClient(this)
        val response = client.get("/api/v1/public/teachers/$STUDENT_ID/calendar")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}