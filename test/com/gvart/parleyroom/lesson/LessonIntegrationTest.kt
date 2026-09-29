package com.gvart.parleyroom.lesson

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.lesson.transfer.CreateLessonRequest
import com.gvart.parleyroom.lesson.transfer.LessonPageResponse
import com.gvart.parleyroom.lesson.transfer.LessonResponse
import com.gvart.parleyroom.lesson.transfer.PublicCalendarResponse
import java.util.UUID
import com.gvart.parleyroom.lesson.transfer.RescheduleLessonRequest
import com.gvart.parleyroom.lesson.transfer.StartLessonResponse
import com.gvart.parleyroom.lesson.transfer.UpdateLessonContentRequest
import com.gvart.parleyroom.video.transfer.VideoAccess
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import com.gvart.parleyroom.lesson.transfer.CancelLessonRequest
import com.gvart.parleyroom.lesson.transfer.PendingRescheduleResponse
import io.ktor.client.request.bearerAuth
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
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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
        assertNull(lesson.rawNotes)
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
        assertEquals(TEACHER_ID, lesson.teacher.id)
        assertEquals("Test", lesson.teacher.firstName)
        assertEquals("Teacher", lesson.teacher.lastName)
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
        lessons.forEach {
            assertEquals(TEACHER_ID, it.teacher.id)
            assertEquals("Test Teacher", "${it.teacher.firstName} ${it.teacher.lastName}")
        }

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
    fun `date range returns every lesson in the period ordered by time`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        // 130 lessons one hour apart from 2027-05-01T00:00Z, inserted latest-first so an
        // unordered read would come back descending. The first 120 fall inside the range.
        transaction {
            exec(
                """
                INSERT INTO lessons (title, type, scheduled_at, teacher_id, status, topic, created_by)
                SELECT 'Bulk ' || n, 'ONE_ON_ONE', TIMESTAMPTZ '2027-05-01T00:00:00Z' + (n * INTERVAL '1 hour'),
                       '$TEACHER_ID', 'CONFIRMED', 'Bulk', '$TEACHER_ID'
                FROM generate_series(129, 0, -1) AS n
                """.trimIndent()
            )
        }

        val response = client.get("/api/v1/lessons?from=2027-05-01T00:00:00Z&to=2027-05-05T23:00:00Z&pageSize=500") {
            bearerAuth(teacherToken)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val page = response.body<LessonPageResponse>()
        assertEquals(120, page.total)
        assertEquals(120, page.lessons.size)
        assertEquals((0 until 120).map { "Bulk $it" }, page.lessons.map { it.title })

        // Without a bounded range the page size stays capped at 100, still in time order.
        val unbounded = client.get("/api/v1/lessons?pageSize=500") { bearerAuth(teacherToken) }
            .body<LessonPageResponse>()
        assertEquals(130, unbounded.total)
        assertEquals((0 until 100).map { "Bulk $it" }, unbounded.lessons.map { it.title })
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

    // -- Notes (lessons.rawNotes, autosaved via PATCH /content) --

    private suspend fun saveNotes(client: HttpClient, token: String, lessonId: String, notes: String): HttpResponse =
        client.patch("/api/v1/lessons/$lessonId/content") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(UpdateLessonContentRequest(rawNotes = notes))
        }

    @Test
    fun `teacher autosaves the lesson notes in every lesson status`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val lesson = createLesson(client, teacherToken).body<LessonResponse>()

        assertEquals("Prep", saveNotes(client, teacherToken, lesson.id, "Prep").body<LessonResponse>().rawNotes)

        client.post("/api/v1/lessons/${lesson.id}/start") { bearerAuth(teacherToken) }
        assertEquals("Prep\ndie Gießkanne", saveNotes(client, teacherToken, lesson.id, "Prep\ndie Gießkanne").body<LessonResponse>().rawNotes)

        client.post("/api/v1/lessons/${lesson.id}/complete") { bearerAuth(teacherToken) }
        assertEquals("After", saveNotes(client, teacherToken, lesson.id, "After").body<LessonResponse>().rawNotes)

        val fetched = client.get("/api/v1/lessons/${lesson.id}") { bearerAuth(teacherToken) }.body<LessonResponse>()
        assertEquals("After", fetched.rawNotes)
    }

    @Test
    fun `students can neither read nor write the lesson notes`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)
        val lesson = createLesson(client, teacherToken).body<LessonResponse>()
        saveNotes(client, teacherToken, lesson.id, "Private")

        assertEquals(HttpStatusCode.Forbidden, saveNotes(client, studentToken, lesson.id, "Hack").status)
        val seen = client.get("/api/v1/lessons/${lesson.id}") { bearerAuth(studentToken) }.body<LessonResponse>()
        assertNull(seen.rawNotes)
    }

    @Test
    fun `sync and reflect endpoints are gone`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val lesson = createLesson(client, teacherToken).body<LessonResponse>()

        val sync = client.put("/api/v1/lessons/${lesson.id}/sync") {
            contentType(ContentType.Application.Json); bearerAuth(teacherToken); setBody("""{ "notes": "x" }""")
        }
        val reflect = client.post("/api/v1/lessons/${lesson.id}/reflect") {
            contentType(ContentType.Application.Json); bearerAuth(getStudentToken(client)); setBody("""{ "studentReflection": "x" }""")
        }
        assertTrue(sync.status == HttpStatusCode.NotFound || sync.status == HttpStatusCode.MethodNotAllowed, "sync: ${sync.status}")
        assertTrue(reflect.status == HttpStatusCode.NotFound || reflect.status == HttpStatusCode.MethodNotAllowed, "reflect: ${reflect.status}")
    }

    // -- Complete --

    @Test
    fun `teacher can complete a started lesson`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()
        client.post("/api/v1/lessons/${lesson.id}/start") { bearerAuth(teacherToken) }

        val response = client.post("/api/v1/lessons/${lesson.id}/complete") { bearerAuth(teacherToken) }

        assertEquals(HttpStatusCode.OK, response.status)
        val completed = response.body<LessonResponse>()
        assertEquals(LessonStatus.COMPLETED, completed.status)
        assertEquals(lesson.id, completed.id)
    }

    @Test
    fun `student cannot complete a lesson`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()
        client.post("/api/v1/lessons/${lesson.id}/start") { bearerAuth(teacherToken) }

        val response = client.post("/api/v1/lessons/${lesson.id}/complete") { bearerAuth(studentToken) }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // -- Video token (early-join window) --

    @Test
    fun `teacher can get video token on a confirmed lesson`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()

        val response = client.post("/api/v1/lessons/${lesson.id}/video-token") {
            bearerAuth(teacherToken)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val access = response.body<VideoAccess>()
        assertEquals("lesson-${lesson.id}", access.roomName)
        assertTrue(access.accessToken.isNotBlank())
    }

    @Test
    fun `student cannot get video token outside early-join window`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        // Scheduled far in the future — well outside the 10 min window
        val lesson = createLesson(
            client,
            teacherToken,
            scheduledAt = "2099-04-10T10:00:00+02:00",
        ).body<LessonResponse>()

        val response = client.post("/api/v1/lessons/${lesson.id}/video-token") {
            bearerAuth(studentToken)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `student can get video token within early-join window`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        // Scheduled ~5 minutes from now — inside the 10 min window
        val scheduled = OffsetDateTime.now().plusMinutes(5)
        val lesson = createLesson(
            client,
            teacherToken,
            scheduledAt = scheduled.toString(),
        ).body<LessonResponse>()

        val response = client.post("/api/v1/lessons/${lesson.id}/video-token") {
            bearerAuth(studentToken)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val access = response.body<VideoAccess>()
        assertTrue(access.accessToken.isNotBlank())
    }

    @Test
    fun `cannot complete lesson that has not started`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()

        val response = client.post("/api/v1/lessons/${lesson.id}/complete") { bearerAuth(teacherToken) }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `cannot complete already completed lesson`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        val lesson = createLesson(client, teacherToken).body<LessonResponse>()
        client.post("/api/v1/lessons/${lesson.id}/start") { bearerAuth(teacherToken) }
        client.post("/api/v1/lessons/${lesson.id}/complete") { bearerAuth(teacherToken) }

        val response = client.post("/api/v1/lessons/${lesson.id}/complete") { bearerAuth(teacherToken) }

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
        assertNull(lesson.rawNotes)

        // Start
        val start = client.post("/api/v1/lessons/${lesson.id}/start") {
            bearerAuth(teacherToken)
        }.body<StartLessonResponse>()
        assertEquals("lesson-${lesson.id}", start.videoRoom.roomName)

        // Verify startedAt is set and status is IN_PROGRESS via GET
        val afterStart = client.get("/api/v1/lessons/${lesson.id}") {
            bearerAuth(teacherToken)
        }.body<LessonResponse>()
        assertNotNull(afterStart.startedAt)
        assertEquals(LessonStatus.IN_PROGRESS, afterStart.status)

        // Teacher takes notes during the lesson
        assertEquals("Working on articles", saveNotes(client, teacherToken, lesson.id, "Working on articles").body<LessonResponse>().rawNotes)

        // Teacher completes; the notes stay
        val complete = client.post("/api/v1/lessons/${lesson.id}/complete") { bearerAuth(teacherToken) }.body<LessonResponse>()
        assertEquals(LessonStatus.COMPLETED, complete.status)
        assertEquals("Working on articles", complete.rawNotes)

        // Final state via GET
        val finalLesson = client.get("/api/v1/lessons/${lesson.id}") {
            bearerAuth(teacherToken)
        }.body<LessonResponse>()
        assertEquals(LessonStatus.COMPLETED, finalLesson.status)
        assertNotNull(finalLesson.startedAt)
        assertEquals("Working on articles", finalLesson.rawNotes)
        assertNull(client.get("/api/v1/lessons/${lesson.id}") { bearerAuth(studentToken) }.body<LessonResponse>().rawNotes)
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
        client.post("/api/v1/lessons/${lesson.id}/complete") { bearerAuth(teacherToken) }

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
        assertNull(busy.rawNotes)
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