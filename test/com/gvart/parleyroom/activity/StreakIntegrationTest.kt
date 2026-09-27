package com.gvart.parleyroom.activity

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.activity.data.ActivityKind
import com.gvart.parleyroom.activity.data.LearningActivityTable
import com.gvart.parleyroom.activity.transfer.StreakResponse
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.homework.data.HomeworkCategory
import com.gvart.parleyroom.homework.data.HomeworkTable
import com.gvart.parleyroom.homework.transfer.SubmitHomeworkRequest
import com.gvart.parleyroom.lesson.data.LessonDocumentTable
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.lesson.data.LessonStudentTable
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.lesson.transfer.CompleteLessonRequest
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.vocabulary.data.VocabCategory
import com.gvart.parleyroom.vocabulary.data.VocabularyWordTable
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// testApp truncates and reseeds on the first request, so every test fetches
// a token before inserting fixtures directly.
class StreakIntegrationTest : IntegrationTest() {

    private val studentUuid = UUID.fromString(STUDENT_ID)
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun today(zone: ZoneId = berlin): LocalDate = LocalDate.now(zone)

    /** Inserts one activity at noon of [date] in [zone]. */
    private fun activityOn(date: LocalDate, userId: UUID = studentUuid, zone: ZoneId = berlin) =
        activityAt(date.atTime(LocalTime.NOON).atZone(zone).toOffsetDateTime(), userId)

    private fun activityAt(at: OffsetDateTime, userId: UUID = studentUuid) = transaction {
        LearningActivityTable.insert {
            it[LearningActivityTable.userId] = userId
            it[kind] = ActivityKind.VOCAB_REVIEW
            it[occurredAt] = at
        }
    }

    private fun setTimezone(userId: UUID, timezone: String) = transaction {
        UserTable.update({ UserTable.id eq userId }) { it[UserTable.timezone] = timezone }
    }

    private fun activityKinds(userId: UUID = studentUuid): List<ActivityKind> = transaction {
        LearningActivityTable.selectAll()
            .where { LearningActivityTable.userId eq userId }
            .map { it[LearningActivityTable.kind] }
    }

    private suspend fun getMyStreak(client: HttpClient, token: String): StreakResponse {
        val response = client.get("/api/v1/users/me/streak") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status)
        return response.body()
    }

    // -- Streak computation --

    @Test
    fun `user without activity has empty streak`() = testApp {
        val client = createJsonClient(this)

        val streak = getMyStreak(client, getStudentToken(client))

        assertEquals(0, streak.current)
        assertEquals(0, streak.longest)
        assertFalse(streak.todayDone)
        assertTrue(streak.week.none { it.active })
    }

    @Test
    fun `reviewing a word today starts a streak`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)
        val wordId = transaction {
            VocabularyWordTable.insertAndGetId {
                it[studentId] = studentUuid
                it[german] = "Hund"
                it[english] = "Dog"
                it[category] = VocabCategory.NOUN
                it[addedAt] = OffsetDateTime.now()
            }.value
        }

        val review = client.post("/api/v1/vocabulary/$wordId/review") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, review.status)

        val streak = getMyStreak(client, token)
        assertEquals(1, streak.current)
        assertEquals(1, streak.longest)
        assertTrue(streak.todayDone)
        assertEquals(listOf(ActivityKind.VOCAB_REVIEW), activityKinds())
    }

    @Test
    fun `activity only yesterday keeps the streak alive`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)
        activityOn(today().minusDays(1))

        val streak = getMyStreak(client, token)

        assertEquals(1, streak.current)
        assertFalse(streak.todayDone)
    }

    @Test
    fun `missing a day breaks the streak`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)
        activityOn(today())
        activityOn(today().minusDays(2))
        activityOn(today().minusDays(3))

        val streak = getMyStreak(client, token)

        assertEquals(1, streak.current)
        assertEquals(2, streak.longest)
        assertTrue(streak.todayDone)
    }

    @Test
    fun `activity older than yesterday leaves current streak at zero`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)
        activityOn(today().minusDays(2))

        val streak = getMyStreak(client, token)

        assertEquals(0, streak.current)
        assertEquals(1, streak.longest)
    }

    @Test
    fun `longest streak is preserved after a break`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)
        (10L..14L).forEach { activityOn(today().minusDays(it)) }
        activityOn(today().minusDays(1))
        activityOn(today())
        // several activities on one day count once
        activityOn(today())

        val streak = getMyStreak(client, token)

        assertEquals(2, streak.current)
        assertEquals(5, streak.longest)
    }

    @Test
    fun `days are bucketed in the user's timezone`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)
        // 23:30 UTC yesterday is already today in Berlin (UTC+1/+2)
        val lateYesterdayUtc = today(berlin).minusDays(1).atTime(23, 30).atOffset(ZoneOffset.UTC)
        activityAt(lateYesterdayUtc)
        setTimezone(studentUuid, "Europe/Berlin")

        val berlinStreak = getMyStreak(client, token)
        assertTrue(berlinStreak.todayDone)
        assertEquals(1, berlinStreak.current)

        setTimezone(studentUuid, "UTC")
        val utcStreak = getMyStreak(client, token)
        assertEquals(1, utcStreak.current)
        // In UTC the event is yesterday — unless UTC's "today" is still that day
        assertEquals(today(ZoneOffset.UTC) == lateYesterdayUtc.toLocalDate(), utcStreak.todayDone)
    }

    @Test
    fun `invalid timezone falls back to UTC`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)
        setTimezone(studentUuid, "Not/AZone")
        activityOn(today(ZoneOffset.UTC), zone = ZoneOffset.UTC)

        val streak = getMyStreak(client, token)

        assertTrue(streak.todayDone)
        assertEquals(today(ZoneOffset.UTC).toString(), streak.week.single { it.active }.date)
    }

    @Test
    fun `week lists the current ISO week Monday to Sunday`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)
        activityOn(today())

        val streak = getMyStreak(client, token)

        assertEquals(7, streak.week.size)
        val dates = streak.week.map { LocalDate.parse(it.date) }
        assertEquals(DayOfWeek.MONDAY, dates.first().dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, dates.last().dayOfWeek)
        dates.zipWithNext().forEach { (a, b) -> assertEquals(a.plusDays(1), b) }
        assertTrue(today() in dates)
        assertEquals(listOf(today().toString()), streak.week.filter { it.active }.map { it.date })
    }

    // -- Recording --

    @Test
    fun `submitting homework records activity`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)
        val homeworkId = transaction {
            HomeworkTable.insertAndGetId {
                it[studentId] = studentUuid
                it[teacherId] = UUID.fromString(TEACHER_ID)
                it[title] = "Essay"
                it[category] = HomeworkCategory.WRITING
                it[createdAt] = OffsetDateTime.now()
                it[updatedAt] = OffsetDateTime.now()
            }.value
        }

        val response = client.post("/api/v1/homework/$homeworkId/submit") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(SubmitHomeworkRequest(submissionText = "Mein Aufsatz"))
        }
        assertEquals(HttpStatusCode.OK, response.status)

        assertEquals(listOf(ActivityKind.HOMEWORK_SUBMITTED), activityKinds())
        assertTrue(getMyStreak(client, token).todayDone)
    }

    @Test
    fun `completing a lesson records activity for each student`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val teacherUuid = UUID.fromString(TEACHER_ID)
        val student2Uuid = UUID.fromString(STUDENT_2_ID)
        val lessonId = transaction {
            val id = LessonTable.insertAndGetId {
                it[title] = "Lesson"
                it[type] = LessonType.SPEAKING_CLUB
                it[scheduledAt] = OffsetDateTime.now()
                it[teacherId] = teacherUuid
                it[status] = LessonStatus.IN_PROGRESS
                it[topic] = "Topic"
                it[createdBy] = teacherUuid
                it[createdAt] = OffsetDateTime.now()
                it[updatedAt] = OffsetDateTime.now()
            }.value
            // started lessons always have a document; completion returns it
            LessonDocumentTable.insert {
                it[LessonDocumentTable.lessonId] = id
                it[createdAt] = OffsetDateTime.now()
                it[updatedAt] = OffsetDateTime.now()
            }
            listOf(studentUuid, student2Uuid).forEach { sid ->
                LessonStudentTable.insert {
                    it[LessonStudentTable.lessonId] = id
                    it[studentId] = sid
                }
            }
            id
        }

        val response = client.post("/api/v1/lessons/$lessonId/complete") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(CompleteLessonRequest())
        }
        assertEquals(HttpStatusCode.OK, response.status)

        assertEquals(listOf(ActivityKind.LESSON_COMPLETED), activityKinds(studentUuid))
        assertEquals(listOf(ActivityKind.LESSON_COMPLETED), activityKinds(student2Uuid))
        assertEquals(emptyList(), activityKinds(teacherUuid))
    }

    // -- Authorization --

    @Test
    fun `student can read own streak by id`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)
        activityOn(today())

        val response = client.get("/api/v1/users/$STUDENT_ID/streak") { bearerAuth(token) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, response.body<StreakResponse>().current)
    }

    @Test
    fun `teacher can read streak of their student`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        activityOn(today())

        val response = client.get("/api/v1/users/$STUDENT_ID/streak") { bearerAuth(token) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, response.body<StreakResponse>().current)
    }

    @Test
    fun `admin can read any user's streak`() = testApp {
        val client = createJsonClient(this)
        val token = getAdminToken(client)

        val response = client.get("/api/v1/users/$STUDENT_2_ID/streak") { bearerAuth(token) }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `unrelated teacher cannot read student's streak`() = testApp {
        val client = createJsonClient(this)
        getAdminToken(client) // boots the app so the fixture below survives reseeding
        val passwordHash = transaction {
            UserTable.selectAll().where { UserTable.id eq studentUuid }.single()[UserTable.passwordHash]
        }
        transaction {
            UserTable.insert {
                it[email] = "teacher2@test.com"
                it[firstName] = "Other"
                it[lastName] = "Teacher"
                it[role] = UserRole.TEACHER
                it[UserTable.passwordHash] = passwordHash
                it[initials] = "OT"
                it[createdAt] = OffsetDateTime.now()
                it[updatedAt] = OffsetDateTime.now()
            }
        }

        val response = client.get("/api/v1/users/$STUDENT_ID/streak") {
            bearerAuth(getToken(client, "teacher2@test.com"))
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `student cannot read another student's streak`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val response = client.get("/api/v1/users/$STUDENT_2_ID/streak") { bearerAuth(token) }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `unknown user returns not found for admin`() = testApp {
        val client = createJsonClient(this)
        val token = getAdminToken(client)

        val response = client.get("/api/v1/users/${UUID.randomUUID()}/streak") { bearerAuth(token) }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `streak requires authentication`() = testApp {
        val client = createJsonClient(this)

        val response = client.get("/api/v1/users/me/streak")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
