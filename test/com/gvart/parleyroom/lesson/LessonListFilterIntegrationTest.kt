package com.gvart.parleyroom.lesson

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.lesson.transfer.CancelLessonRequest
import com.gvart.parleyroom.lesson.transfer.CreateLessonRequest
import com.gvart.parleyroom.lesson.transfer.LessonPageResponse
import com.gvart.parleyroom.lesson.transfer.LessonResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LessonListFilterIntegrationTest : IntegrationTest() {

    private val teacher2Id = "00000000-0000-0000-0000-000000000005"

    private fun linkStudent2() = transaction {
        exec(
            "INSERT INTO teacher_students (teacher_id, student_id, lesson_types, status, started_at) " +
                    "VALUES ('$TEACHER_ID', '$STUDENT_2_ID', '{ONE_ON_ONE}', 'ACTIVE', now())"
        )
    }

    /** A second teacher who also teaches STUDENT_ID; same password as the seeded users. */
    private fun seedTeacher2() = transaction {
        exec(
            "INSERT INTO users (id, email, first_name, last_name, password_hash, role, initials, created_at, updated_at) " +
                    "SELECT '$teacher2Id', 'teacher2@test.com', 'Other', 'Teacher', password_hash, 'TEACHER', 'OT', now(), now() " +
                    "FROM users WHERE id = '$TEACHER_ID'"
        )
        exec(
            "INSERT INTO teacher_students (teacher_id, student_id, lesson_types, status, started_at) " +
                    "VALUES ('$teacher2Id', '$STUDENT_ID', '{ONE_ON_ONE}', 'ACTIVE', now())"
        )
    }

    /** Lessons can only be created in the future; tests move them to the past directly. */
    private fun moveToPast(lessonId: String, daysAgo: Long, status: LessonStatus = LessonStatus.COMPLETED) = transaction {
        exec(
            "UPDATE lessons SET scheduled_at = now() - interval '$daysAgo days', status = '${status.name}' " +
                    "WHERE id = '$lessonId'"
        )
    }

    private suspend fun create(
        client: HttpClient,
        token: String,
        studentId: String,
        scheduledAt: String,
        teacherId: String = TEACHER_ID,
    ): LessonResponse {
        val response = client.post("/api/v1/lessons") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(
                CreateLessonRequest(
                    teacherId = teacherId,
                    studentIds = listOf(studentId),
                    title = "Lesson",
                    type = LessonType.ONE_ON_ONE,
                    scheduledAt = OffsetDateTime.parse(scheduledAt),
                    topic = "Topic",
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }

    private suspend fun list(client: HttpClient, token: String, query: String): LessonPageResponse {
        val response = client.get("/api/v1/lessons?$query") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status)
        return response.body()
    }

    @Test
    fun `teacher filters by student, status, upcoming and order`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        linkStudent2()

        val s1Future = create(client, teacher, STUDENT_ID, "2027-05-01T10:00:00+02:00")
        val s1Later = create(client, teacher, STUDENT_ID, "2027-05-08T10:00:00+02:00")
        val s1Old = create(client, teacher, STUDENT_ID, "2027-05-15T10:00:00+02:00")
        val s1Older = create(client, teacher, STUDENT_ID, "2027-05-22T10:00:00+02:00")
        val s2Future = create(client, teacher, STUDENT_2_ID, "2027-05-02T10:00:00+02:00")
        moveToPast(s1Old.id, daysAgo = 2)
        moveToPast(s1Older.id, daysAgo = 9)
        client.post("/api/v1/lessons/${s1Later.id}/cancel") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacher)
            setBody(CancelLessonRequest("ill"))
        }.also { assertEquals(HttpStatusCode.OK, it.status) }

        val all = list(client, teacher, "pageSize=100")
        assertEquals(5, all.total)

        val student1 = list(client, teacher, "studentId=$STUDENT_ID")
        assertEquals(listOf(s1Older.id, s1Old.id, s1Future.id, s1Later.id), student1.lessons.map { it.id })
        assertEquals(4, student1.total)
        assertEquals(listOf(s2Future.id), list(client, teacher, "studentId=$STUDENT_2_ID").lessons.map { it.id })

        // Next lesson of a student: upcoming, still on, soonest first.
        val next = list(client, teacher, "studentId=$STUDENT_ID&upcoming=true&status=CONFIRMED&status=IN_PROGRESS&pageSize=1")
        assertEquals(listOf(s1Future.id), next.lessons.map { it.id })
        assertEquals(1, next.total)

        // History: past lessons, most recent first.
        val history = list(client, teacher, "studentId=$STUDENT_ID&upcoming=false&order=desc")
        assertEquals(listOf(s1Old.id, s1Older.id), history.lessons.map { it.id })

        val commaList = list(client, teacher, "status=COMPLETED,CANCELLED&order=desc")
        assertEquals(listOf(s1Older.id, s1Old.id, s1Later.id).toSet(), commaList.lessons.map { it.id }.toSet())
        assertEquals(LessonStatus.CANCELLED, commaList.lessons.first().status)
    }

    @Test
    fun `teacher filtering by a student only sees their own lessons with that student`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        seedTeacher2()
        val teacher2 = getToken(client, "teacher2@test.com")

        val mine = create(client, teacher, STUDENT_ID, "2027-05-01T10:00:00+02:00")
        val theirs = create(client, teacher2, STUDENT_ID, "2027-05-01T12:00:00+02:00", teacherId = teacher2Id)

        assertEquals(listOf(mine.id), list(client, teacher, "studentId=$STUDENT_ID").lessons.map { it.id })
        assertEquals(listOf(theirs.id), list(client, teacher2, "studentId=$STUDENT_ID").lessons.map { it.id })
        assertEquals(2, list(client, getAdminToken(client), "studentId=$STUDENT_ID").total)
    }

    @Test
    fun `student filter is limited to their own id`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        linkStudent2()
        val own = create(client, teacher, STUDENT_ID, "2027-05-01T10:00:00+02:00")
        create(client, teacher, STUDENT_2_ID, "2027-05-01T12:00:00+02:00")
        val student = getStudentToken(client)

        // Without a filter the teacher's other 1:1 shows as a busy block.
        assertEquals(2, list(client, student, "").total)
        assertEquals(listOf(own.id), list(client, student, "studentId=$STUDENT_ID").lessons.map { it.id })

        val forbidden = client.get("/api/v1/lessons?studentId=$STUDENT_2_ID") { bearerAuth(student) }
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
    }

    @Test
    fun `invalid filter values are rejected with LESSON_FILTER_INVALID`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)

        for ((query, pointer) in listOf("status=DONE" to "/status", "upcoming=yes" to "/upcoming", "order=up" to "/order")) {
            val response = client.get("/api/v1/lessons?$query") { bearerAuth(teacher) }
            assertEquals(HttpStatusCode.BadRequest, response.status, query)
            val problem = response.body<ProblemDetail>()
            assertEquals("LESSON_FILTER_INVALID", problem.code)
            assertEquals(pointer, problem.pointer)
        }
    }

    @Test
    fun `oversized pageSize is clamped and the effective size is returned`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)

        assertEquals(100, list(client, teacher, "pageSize=1000").pageSize)
        val range = list(client, teacher, "pageSize=1000&from=2027-01-01T00:00:00Z&to=2027-12-31T00:00:00Z")
        assertEquals(500, range.pageSize)
        assertTrue(range.lessons.isEmpty())
    }

    @Test
    fun `lesson participants carry avatarUrl`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        transaction { exec("UPDATE users SET avatar_url = 'avatars/$STUDENT_ID/s.png' WHERE id = '$STUDENT_ID'") }
        create(client, teacher, STUDENT_ID, "2027-05-01T10:00:00+02:00")

        val lesson = list(client, teacher, "studentId=$STUDENT_ID").lessons.single()
        assertEquals("/api/v1/users/$STUDENT_ID/avatar?v=s.png", lesson.students.single().avatarUrl)
        assertEquals(null, lesson.teacher?.avatarUrl)
    }
}
