package com.gvart.parleyroom.lesson

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.lesson.data.LessonStudentStatus
import com.gvart.parleyroom.lesson.transfer.CreateLessonRequest
import com.gvart.parleyroom.lesson.transfer.LessonPageResponse
import com.gvart.parleyroom.lesson.transfer.LessonResponse
import com.gvart.parleyroom.lesson.transfer.OpenClubResponse
import com.gvart.parleyroom.notification.data.NotificationType
import com.gvart.parleyroom.notification.transfer.NotificationPageResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClubIntegrationTest : IntegrationTest() {

    private suspend fun createClub(
        client: HttpClient,
        token: String,
        maxParticipants: Int? = 6,
        studentIds: List<String> = emptyList(),
        scheduledAt: String = "2027-04-10T18:00:00+02:00",
        type: LessonType = LessonType.SPEAKING_CLUB,
    ): LessonResponse = client.post("/api/v1/lessons") {
        contentType(ContentType.Application.Json)
        bearerAuth(token)
        setBody(
            CreateLessonRequest(
                teacherId = TEACHER_ID,
                studentIds = studentIds,
                title = "Stammtisch",
                type = type,
                scheduledAt = OffsetDateTime.parse(scheduledAt),
                durationMinutes = 60,
                topic = "Reisen",
                maxParticipants = maxParticipants,
            )
        )
    }.body()

    private fun linkStudent2() = transaction {
        exec(
            "INSERT INTO teacher_students (teacher_id, student_id, lesson_types, status, started_at) " +
                "VALUES ('$TEACHER_ID', '$STUDENT_2_ID', '{ONE_ON_ONE}', 'ACTIVE', now())"
        )
    }

    private suspend fun join(client: HttpClient, lessonId: String, token: String): HttpResponse =
        client.post("/api/v1/lessons/$lessonId/join") { bearerAuth(token) }

    private suspend fun withdraw(client: HttpClient, lessonId: String, token: String): HttpResponse =
        client.delete("/api/v1/lessons/$lessonId/join") { bearerAuth(token) }

    private suspend fun openClubs(client: HttpClient, token: String): List<OpenClubResponse> =
        client.get("/api/v1/lessons/open-clubs") { bearerAuth(token) }.body()

    private suspend fun joinRequestNotifications(client: HttpClient, teacherToken: String): Int =
        client.get("/api/v1/notifications") { bearerAuth(teacherToken) }
            .body<NotificationPageResponse>().notifications
            .count { it.type == NotificationType.JOIN_REQUESTED }

    private suspend fun HttpResponse.code(): String? = body<ProblemDetail>().code

    @Test
    fun `pending request holds a spot so the next student sees CLUB_FULL`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val student = getStudentToken(client)
        val student2 = getStudent2Token(client)
        linkStudent2()

        val club = createClub(client, teacher, maxParticipants = 1)

        assertEquals(HttpStatusCode.Created, join(client, club.id, student2).status)

        val full = join(client, club.id, student)
        assertEquals(HttpStatusCode.Conflict, full.status)
        assertEquals("CLUB_FULL", full.code())

        val seen = openClubs(client, student).single()
        assertEquals(1, seen.takenSpots)
        assertEquals(1, seen.maxParticipants)
        assertNull(seen.myStatus)
    }

    @Test
    fun `withdrawing a pending request frees the spot`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val student = getStudentToken(client)
        val student2 = getStudent2Token(client)
        linkStudent2()

        val club = createClub(client, teacher, maxParticipants = 1)
        join(client, club.id, student2)

        assertEquals(HttpStatusCode.NoContent, withdraw(client, club.id, student2).status)
        assertNull(openClubs(client, student2).single().myStatus)
        assertEquals(0, openClubs(client, student2).single().takenSpots)

        assertEquals(HttpStatusCode.Created, join(client, club.id, student).status)
    }

    @Test
    fun `withdraw without a pending request is 404`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val student = getStudentToken(client)

        // Confirmed participants leave through cancellation, not withdraw.
        val club = createClub(client, teacher, studentIds = listOf(STUDENT_ID))

        val response = withdraw(client, club.id, student)
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("JOIN_REQUEST_NOT_FOUND", response.code())
    }

    @Test
    fun `re-request after rejection is capacity checked and notifies the teacher`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val student = getStudentToken(client)
        val student2 = getStudent2Token(client)
        linkStudent2()

        val club = createClub(client, teacher, maxParticipants = 1)
        join(client, club.id, student2)
        client.post("/api/v1/lessons/${club.id}/participants/$STUDENT_2_ID/reject") { bearerAuth(teacher) }
        assertEquals(LessonStudentStatus.REJECTED, openClubs(client, student2).single().myStatus)
        // A rejected request no longer holds a spot.
        assertEquals(0, openClubs(client, student2).single().takenSpots)

        // Someone else takes the spot; the rejected student can't re-request into a full club.
        join(client, club.id, student)
        val full = join(client, club.id, student2)
        assertEquals(HttpStatusCode.Conflict, full.status)
        assertEquals("CLUB_FULL", full.code())

        withdraw(client, club.id, student)
        val before = joinRequestNotifications(client, teacher)
        assertEquals(HttpStatusCode.Created, join(client, club.id, student2).status)
        assertEquals(before + 1, joinRequestNotifications(client, teacher))
        assertEquals(LessonStudentStatus.REQUESTED, openClubs(client, student2).single().myStatus)
    }

    @Test
    fun `accept re-checks confirmed capacity`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val student2 = getStudent2Token(client)
        linkStudent2()

        val club = createClub(client, teacher, maxParticipants = 2, studentIds = listOf(STUDENT_ID))
        join(client, club.id, student2)
        // Teacher shrinks the club to the one confirmed seat.
        transaction { exec("UPDATE lessons SET max_participants = 1 WHERE id = '${club.id}'") }

        val response = client.post("/api/v1/lessons/${club.id}/participants/$STUDENT_2_ID/accept") {
            bearerAuth(teacher)
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("CLUB_FULL", response.code())
    }

    @Test
    fun `student not linked to the teacher cannot join or see the club`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val student2 = getStudent2Token(client)

        val club = createClub(client, teacher)

        val response = join(client, club.id, student2)
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("NOT_TEACHERS_STUDENT", response.code())
        assertTrue(openClubs(client, student2).isEmpty())
    }

    @Test
    fun `open clubs lists upcoming clubs with counts and my status but no names`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val student2 = getStudent2Token(client)
        linkStudent2()

        val later = createClub(client, teacher, maxParticipants = 6, studentIds = listOf(STUDENT_ID), scheduledAt = "2027-05-01T18:00:00+02:00")
        val sooner = createClub(client, teacher, maxParticipants = 3, type = LessonType.READING_CLUB, scheduledAt = "2027-04-01T18:00:00+02:00")
        join(client, sooner.id, student2)
        // Not listed: a 1:1, a cancelled club, and a past club.
        client.post("/api/v1/lessons") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacher)
            setBody(
                CreateLessonRequest(
                    teacherId = TEACHER_ID, studentIds = listOf(STUDENT_ID), title = "1:1",
                    type = LessonType.ONE_ON_ONE, scheduledAt = OffsetDateTime.parse("2027-04-02T10:00:00+02:00"),
                    durationMinutes = 60, topic = "x",
                )
            )
        }
        val cancelled = createClub(client, teacher, scheduledAt = "2027-04-03T18:00:00+02:00")
        transaction { exec("UPDATE lessons SET status = 'CANCELLED' WHERE id = '${cancelled.id}'") }
        val past = createClub(client, teacher, scheduledAt = "2027-04-04T18:00:00+02:00")
        transaction { exec("UPDATE lessons SET scheduled_at = now() - interval '1 day' WHERE id = '${past.id}'") }

        val response = client.get("/api/v1/lessons/open-clubs") { bearerAuth(student2) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertFalse(response.bodyAsText().contains("Student"), "other students must not be named")

        val clubs = response.body<List<OpenClubResponse>>()
        assertEquals(listOf(sooner.id, later.id), clubs.map { it.id })
        assertEquals(LessonType.READING_CLUB, clubs[0].type)
        assertEquals(1, clubs[0].takenSpots)
        assertEquals(LessonStudentStatus.REQUESTED, clubs[0].myStatus)
        assertEquals(1, clubs[1].takenSpots)
        assertEquals(6, clubs[1].maxParticipants)
        assertNull(clubs[1].myStatus)
        assertEquals("Test", clubs[1].teacher.firstName)

        // A pending request doesn't reveal the roster in the lesson list either.
        val laterAsSeen = client.get("/api/v1/lessons") { bearerAuth(student2) }
            .body<LessonPageResponse>().lessons.single { it.id == later.id }
        assertTrue(laterAsSeen.students.isEmpty())
        join(client, later.id, student2)
        val pendingAsSeen = client.get("/api/v1/lessons") { bearerAuth(student2) }
            .body<LessonPageResponse>().lessons.single { it.id == later.id }
        assertTrue(pendingAsSeen.students.isEmpty())

        val forStudent = openClubs(client, getStudentToken(client))
        assertEquals(LessonStudentStatus.CONFIRMED, forStudent.single { it.id == later.id }.myStatus)
    }

    @Test
    fun `teachers cannot browse open clubs`() = testApp {
        val client = createJsonClient(this)
        val response = client.get("/api/v1/lessons/open-clubs") { bearerAuth(getTeacherToken(client)) }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `concurrent requests for the last spot admit exactly one`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val student = getStudentToken(client)
        val student2 = getStudent2Token(client)
        linkStudent2()

        val club = createClub(client, teacher, maxParticipants = 1)

        val statuses = coroutineScope {
            listOf(student, student2, student, student2)
                .map { token -> async { join(client, club.id, token).status } }
                .awaitAll()
        }

        assertEquals(1, statuses.count { it == HttpStatusCode.Created }, "statuses: $statuses")
        assertTrue(statuses.all { it == HttpStatusCode.Created || it == HttpStatusCode.Conflict }, "statuses: $statuses")
        assertEquals(1, openClubs(client, student).single().takenSpots)
    }
}
