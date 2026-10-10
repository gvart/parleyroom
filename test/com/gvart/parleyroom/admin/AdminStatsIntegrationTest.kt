package com.gvart.parleyroom.admin

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.admin.transfer.AdminStatsResponse
import com.gvart.parleyroom.admin.transfer.AdminUserResponse
import com.gvart.parleyroom.admin.transfer.LessonDay
import com.gvart.parleyroom.admin.transfer.PracticeDay
import com.gvart.parleyroom.admin.transfer.TopTeacher
import com.gvart.parleyroom.homework.data.HomeworkStatus
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.user.TelegramInitData
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserStatus
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.transfer.AuthenticateRequest
import com.gvart.parleyroom.user.transfer.AuthenticateResponse
import com.gvart.parleyroom.user.transfer.RefreshTokenRequest
import com.gvart.parleyroom.user.transfer.TelegramAuthRequest
import com.gvart.parleyroom.vocabulary.data.StudentVocabStatus
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminStatsIntegrationTest : IntegrationTest() {

    // -------- last_login_at --------

    @Test
    fun `password login, refresh and mini app sign-in stamp last_login_at`() = testApp {
        val client = createJsonClient(this)
        assertNull(lastLoginAt(STUDENT_ID))

        val tokens = client.post("/api/v1/token") {
            contentType(ContentType.Application.Json)
            setBody(AuthenticateRequest("student@test.com", TEST_PASSWORD))
        }.body<AuthenticateResponse>()
        val afterLogin = assertNotNull(lastLoginAt(STUDENT_ID))

        resetLastLogin(STUDENT_ID)
        val refresh = client.post("/api/v1/token/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshTokenRequest(tokens.refreshToken))
        }
        assertEquals(HttpStatusCode.OK, refresh.status)
        assertTrue(assertNotNull(lastLoginAt(STUDENT_ID)).isAfter(afterLogin.minusSeconds(1)))

        resetLastLogin(STUDENT_ID)
        transaction {
            UserTable.update({ UserTable.id eq UUID.fromString(STUDENT_ID) }) { it[telegramId] = 555_001L }
        }
        val miniApp = client.post("/api/v1/token/telegram-miniapp") {
            contentType(ContentType.Application.Json)
            setBody(TelegramAuthRequest(TelegramInitData.build(TEST_TELEGRAM_BOT_TOKEN, userId = 555_001L)))
        }
        assertEquals(HttpStatusCode.OK, miniApp.status)
        assertNotNull(lastLoginAt(STUDENT_ID))
    }

    @Test
    fun `failed login does not stamp last_login_at`() = testApp {
        val client = createJsonClient(this)
        val response = client.post("/api/v1/token") {
            contentType(ContentType.Application.Json)
            setBody(AuthenticateRequest("student@test.com", "wrong-password"))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertNull(lastLoginAt(STUDENT_ID))
    }

    @Test
    fun `admin user detail exposes lastLoginAt`() = testApp {
        val client = createJsonClient(this)
        val adminToken = getAdminToken(client)

        val student = client.get("/api/v1/admin/users/$STUDENT_ID") { bearerAuth(adminToken) }.body<AdminUserResponse>()
        assertNull(student.lastLoginAt)
        val admin = client.get("/api/v1/admin/users/$ADMIN_ID") { bearerAuth(adminToken) }.body<AdminUserResponse>()
        assertNotNull(admin.lastLoginAt)
    }

    // -------- Authorization & validation --------

    @Test
    fun `teacher and student cannot read stats`() = testApp {
        val client = createJsonClient(this)
        for (token in listOf(getTeacherToken(client), getStudentToken(client))) {
            val response = client.get("/api/v1/admin/stats") { bearerAuth(token) }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
    }

    @Test
    fun `unauthenticated stats request returns 401`() = testApp {
        val client = createJsonClient(this)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/admin/stats").status)
    }

    @Test
    fun `invalid ranges return 400`() = testApp {
        val client = createJsonClient(this)
        val adminToken = getAdminToken(client)
        for (query in listOf(
            "from=2026-01-10&to=2026-01-01",
            "from=2025-01-01&to=2026-01-02",
            "from=not-a-date",
            "to=2026-13-01",
        )) {
            val response = client.get("/api/v1/admin/stats?$query") { bearerAuth(adminToken) }
            assertEquals(HttpStatusCode.BadRequest, response.status, query)
        }
        val maxRange = client.get("/api/v1/admin/stats?from=2025-01-01&to=2026-01-01") { bearerAuth(adminToken) }
        assertEquals(HttpStatusCode.OK, maxRange.status)
    }

    // -------- Stats --------

    @Test
    fun `default range is the last 30 UTC days and includes today's signups`() = testApp {
        val client = createJsonClient(this)
        val stats = client.get("/api/v1/admin/stats") { bearerAuth(getAdminToken(client)) }.body<AdminStatsResponse>()

        val today = LocalDate.now(ZoneOffset.UTC)
        assertEquals(today.toString(), stats.to)
        assertEquals(today.minusDays(29).toString(), stats.from)
        assertEquals(30, stats.users.signupsByDay.size)
        assertEquals(today.toString(), stats.users.signupsByDay.last().date)
        // 4 fixture users + the bootstrap admin created on ApplicationStarted
        assertEquals(5, stats.users.signupsByDay.last().count)
        assertEquals(5, stats.users.newInRange)
        assertEquals(30, stats.lessons.byDay.size)
        assertEquals(30, stats.practice.byDay.size)
    }

    @Test
    fun `stats aggregate fixture data over a fixed range`() = testApp {
        val client = createJsonClient(this)
        val adminToken = getAdminToken(client) // admin: last_login_at = now
        insertFixtures()

        val response = client.get("/api/v1/admin/stats?from=2026-01-01&to=2026-01-07") { bearerAuth(adminToken) }
        assertEquals(HttpStatusCode.OK, response.status)
        val stats = response.body<AdminStatsResponse>()
        val days = (1..7).map { "2026-01-0$it" }

        assertEquals("2026-01-01", stats.from)
        assertEquals("2026-01-07", stats.to)

        with(stats.users) {
            assertEquals(5, total) // incl. the bootstrap admin
            assertEquals(mapOf(UserRole.ADMIN to 2L, UserRole.TEACHER to 1L, UserRole.STUDENT to 2L), byRole)
            assertEquals(mapOf(UserStatus.ACTIVE to 5L, UserStatus.REQUEST to 0L, UserStatus.INACTIVE to 0L), byStatus)
            assertEquals(0, newInRange)
            assertEquals(days, signupsByDay.map { it.date })
            assertTrue(signupsByDay.all { it.count == 0L })
            assertEquals(2, active7d) // admin (login) + student2 (activity 2d ago)
            assertEquals(3, active30d) // + student (activity 10d ago)
        }

        with(stats.lessons) {
            assertEquals(
                mapOf(
                    LessonStatus.CONFIRMED to 1L, LessonStatus.REQUEST to 0L, LessonStatus.CANCELLED to 1L,
                    LessonStatus.COMPLETED to 5L, LessonStatus.IN_PROGRESS to 0L,
                ),
                byStatus,
            )
            assertEquals(4, scheduledInRange)
            assertEquals(3, completedInRange)
            assertEquals(1, cancelledInRange)
            assertEquals(
                listOf(
                    LessonDay("2026-01-01", 0, 0),
                    LessonDay("2026-01-02", 1, 0),
                    LessonDay("2026-01-03", 1, 0),
                    LessonDay("2026-01-04", 1, 1),
                    LessonDay("2026-01-05", 0, 0),
                    LessonDay("2026-01-06", 0, 0),
                    LessonDay("2026-01-07", 0, 0),
                ),
                byDay,
            )
            assertEquals(
                listOf(TopTeacher(TEACHER_ID, "Test", "Teacher", 2), TopTeacher(ADMIN_ID, "Test", "Admin", 1)),
                topTeachers,
            )
        }

        with(stats.homework) {
            assertEquals(
                mapOf(
                    HomeworkStatus.OPEN to 1L, HomeworkStatus.SUBMITTED to 1L,
                    HomeworkStatus.REVIEWED to 1L, HomeworkStatus.DONE to 1L,
                ),
                byStatus,
            )
            assertEquals(3, assignedInRange)
            assertEquals(3, submittedInRange)
            assertEquals(2, reviewedInRange)
            assertEquals(14.0, assertNotNull(avgReviewHours), 0.001)
        }

        with(stats.practice) {
            assertEquals(2, vocabReviewsInRange)
            assertEquals(1, sentencesInRange)
            assertEquals(1, activeLearners7d)
            assertEquals(days, byDay.map { it.date })
            assertEquals(PracticeDay("2026-01-02", 1, 0, 0, 1), byDay[1])
            assertEquals(PracticeDay("2026-01-03", 0, 0, 1, 1), byDay[2])
            assertEquals(PracticeDay("2026-01-04", 1, 1, 1, 1), byDay[3])
            assertEquals(PracticeDay("2026-01-06", 0, 0, 1, 0), byDay[5])
        }

        with(stats.content) {
            assertEquals(0, materials)
            assertEquals(0, documents)
            assertEquals(1, vocabEntries)
            assertEquals(
                mapOf(
                    StudentVocabStatus.NEW to 0L, StudentVocabStatus.LEARNING to 1L,
                    StudentVocabStatus.REVIEW to 0L, StudentVocabStatus.LEARNED to 0L,
                ),
                studentVocabByStatus,
            )
        }
    }

    @Test
    fun `avgReviewHours is null when nothing was reviewed in range`() = testApp {
        val client = createJsonClient(this)
        val stats = client.get("/api/v1/admin/stats?from=2026-01-01&to=2026-01-07") {
            bearerAuth(getAdminToken(client))
        }.body<AdminStatsResponse>()
        assertNull(stats.homework.avgReviewHours)
        assertTrue(stats.lessons.topTeachers.isEmpty())
    }

    private fun lastLoginAt(userId: String): OffsetDateTime? = transaction {
        UserTable.selectAll().where { UserTable.id eq UUID.fromString(userId) }.single()[UserTable.lastLoginAt]
    }

    private fun resetLastLogin(userId: String) = transaction {
        UserTable.update({ UserTable.id eq UUID.fromString(userId) }) { it[lastLoginAt] = null }
    }

    /**
     * Range 2026-01-01..2026-01-07. Lessons: 3 completed in range (teacher x2, admin x1), 2 completed
     * just outside the edges, 1 cancelled on 01-04, 1 confirmed. Homework: reviews taking 4h and 24h.
     */
    private fun insertFixtures() = transaction {
        val t = "'$TEACHER_ID'"
        val a = "'$ADMIN_ID'"
        val s = "'$STUDENT_ID'"
        exec(
            """
            INSERT INTO lessons (id, title, type, scheduled_at, teacher_id, status, topic, created_by, ended_at, cancelled_at) VALUES
              ('10000000-0000-0000-0000-000000000001', 'L1', 'ONE_ON_ONE', '2026-01-02T10:00Z', $t, 'COMPLETED', 't', $t, '2026-01-02T11:00Z', NULL),
              ('10000000-0000-0000-0000-000000000002', 'L2', 'ONE_ON_ONE', '2026-01-03T23:30Z', $a, 'COMPLETED', 't', $a, NULL, NULL),
              ('10000000-0000-0000-0000-000000000003', 'L3', 'ONE_ON_ONE', '2026-01-05T10:00Z', $t, 'CANCELLED', 't', $t, NULL, '2026-01-04T08:00Z'),
              ('10000000-0000-0000-0000-000000000004', 'L4', 'ONE_ON_ONE', '2026-01-06T10:00Z', $t, 'CONFIRMED', 't', $t, NULL, NULL),
              ('10000000-0000-0000-0000-000000000005', 'L5', 'ONE_ON_ONE', '2025-12-31T22:00Z', $t, 'COMPLETED', 't', $t, '2025-12-31T23:59Z', NULL),
              ('10000000-0000-0000-0000-000000000006', 'L6', 'ONE_ON_ONE', '2026-01-08T00:00Z', $t, 'COMPLETED', 't', $t, '2026-01-08T00:00Z', NULL),
              ('10000000-0000-0000-0000-000000000007', 'L7', 'ONE_ON_ONE', '2026-01-04T09:00Z', $t, 'COMPLETED', 't', $t, '2026-01-04T10:00Z', NULL);

            INSERT INTO assignments (id, teacher_id, title, item_count, total_units) VALUES
              ('20000000-0000-0000-0000-000000000001', $t, 'A1', 0, 0),
              ('20000000-0000-0000-0000-000000000002', $t, 'A2', 0, 0),
              ('20000000-0000-0000-0000-000000000003', $t, 'A3', 0, 0),
              ('20000000-0000-0000-0000-000000000004', $t, 'A4', 0, 0);

            INSERT INTO homework (assignment_id, student_id, status, created_at, submitted_at, reviewed_at) VALUES
              ('20000000-0000-0000-0000-000000000001', $s, 'REVIEWED',  '2026-01-02T08:00Z', '2026-01-03T10:00Z', '2026-01-03T14:00Z'),
              ('20000000-0000-0000-0000-000000000002', $s, 'DONE',      '2026-01-02T09:00Z', '2026-01-04T00:00Z', '2026-01-05T00:00Z'),
              ('20000000-0000-0000-0000-000000000003', $s, 'OPEN',      '2025-12-20T09:00Z', NULL, NULL),
              ('20000000-0000-0000-0000-000000000004', $s, 'SUBMITTED', '2026-01-06T07:00Z', '2026-01-06T09:00Z', NULL);

            INSERT INTO vocab_entries (id, teacher_id, lemma, word_type, translations, synonyms, created_at, updated_at) VALUES
              ('30000000-0000-0000-0000-000000000001', $t, 'Haus', 'NOUN', '{"ru": "дом"}', '{}', now(), now());
            INSERT INTO student_vocab (id, student_id, vocab_entry_id, status, added_at) VALUES
              ('30000000-0000-0000-0000-000000000002', $s, '30000000-0000-0000-0000-000000000001', 'LEARNING', now());
            INSERT INTO vocab_reviews (student_vocab_id, student_id, rating, mode, state_before, reviewed_at) VALUES
              ('30000000-0000-0000-0000-000000000002', $s, 'GOOD', 'DE_TO_MEANING', 0, '2026-01-02T12:00Z'),
              ('30000000-0000-0000-0000-000000000002', $s, 'GOOD', 'DE_TO_MEANING', 1, '2026-01-04T12:00Z'),
              ('30000000-0000-0000-0000-000000000002', $s, 'GOOD', 'DE_TO_MEANING', 2, '2026-01-09T12:00Z');
            INSERT INTO student_vocab_sentences (student_vocab_id, student_id, sentence, feedback, created_at) VALUES
              ('30000000-0000-0000-0000-000000000002', $s, 'Das Haus ist groß.',
               '{"isCorrect": true, "corrected": "Das Haus ist groß.", "explanation": "", "usesWord": true}', '2026-01-04T13:00Z');

            INSERT INTO learning_activity (user_id, kind, occurred_at) VALUES
              ('$STUDENT_2_ID', 'VOCAB_REVIEW', now() - interval '2 days'),
              ($s, 'VOCAB_REVIEW', now() - interval '10 days');
            """.trimIndent()
        )
    }
}
