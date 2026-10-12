package com.gvart.parleyroom.reminder

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.ai.STUDENT
import com.gvart.parleyroom.ai.TEACHER
import com.gvart.parleyroom.goal.GoalFixtures
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.lessonprep.LessonPrepFixtures
import com.gvart.parleyroom.reminder.service.AttentionFeatures
import com.gvart.parleyroom.reminder.service.TeacherAttentionService
import com.gvart.parleyroom.reminder.transfer.AttentionReasonType
import com.gvart.parleyroom.reminder.transfer.AttentionStudent
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.di.dependencies
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TeacherAttentionIntegrationTest : IntegrationTest() {

    private val now: OffsetDateTime get() = OffsetDateTime.now()

    private suspend fun HttpClient.attention(token: String): List<AttentionStudent> {
        val response = get("/api/v1/teacher/attention") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status)
        return response.body()
    }

    /** STUDENT: BEHIND, a PARTLY mismatch, a 1:1 not wrapped up, no homework. Returns the open lesson. */
    private fun seedTroubledStudent(): UUID {
        LessonPrepFixtures.roadmap()
        val wrapped = LessonPrepFixtures.lesson(now.minusDays(3))
        LessonPrepFixtures.plan(wrapped)
        LessonPrepFixtures.lesson(now.minusDays(20)) // older than 14 days
        return LessonPrepFixtures.lesson(now.minusDays(2), notes = "privat")
    }

    @Test
    fun `lists own students with every reason, most severe first`() = testApp {
        startApplication()
        TeacherReminderFixtures.flags("teacherReminders", "roadmap", "lessonPrep")
        val open = seedTroubledStudent()
        val mia = TeacherReminderFixtures.linkedStudent("Mia")
        val ben = TeacherReminderFixtures.linkedStudent("Ben")
        val bens = LessonPrepFixtures.lesson(now.minusDays(1), student = ben)
        LessonPrepFixtures.plan(bens, student = ben)
        LessonPrepFixtures.homework(bens, "Perfekt", student = ben)

        val client = createJsonClient(this)
        val list = client.attention(getTeacherToken(client))
        assertEquals(listOf(STUDENT.toString(), mia.toString()), list.map { it.studentId }, "Ben is fine; most severe first")
        val first = list.first()
        assertEquals("Test Student", first.studentName)
        assertEquals(
            listOf(AttentionReasonType.BEHIND, AttentionReasonType.MISMATCH, AttentionReasonType.NOT_WRAPPED_UP, AttentionReasonType.NO_HOMEWORK),
            first.reasons.map { it.type },
        )
        val wrap = first.reasons.single { it.type == AttentionReasonType.NOT_WRAPPED_UP }
        assertEquals(open.toString(), wrap.refId)
        assertEquals("Lektion", wrap.detail)
        assertEquals("Begrüßung", first.reasons.single { it.type == AttentionReasonType.MISMATCH }.detail)
        assertTrue(first.reasons.single { it.type == AttentionReasonType.BEHIND }.detail!!.matches(Regex("\\d+/\\d+")))
        assertEquals(listOf(AttentionReasonType.NO_HOMEWORK), list[1].reasons.map { it.type })

        // Another teacher sees none of them.
        GoalFixtures.secondTeacher()
        assertEquals(emptyList(), client.attention(getToken(client, "teacher2@test.com")))
    }

    @Test
    fun `reasons of features that are off are left out`() = testApp {
        startApplication()
        TeacherReminderFixtures.flags("teacherReminders")
        seedTroubledStudent()
        val client = createJsonClient(this)
        val list = client.attention(getTeacherToken(client))
        assertEquals(listOf(AttentionReasonType.NO_HOMEWORK), list.single().reasons.map { it.type })
    }

    @Test
    fun `the roadmap flag alone makes it available, wrap-ups counted for students with a goal`() = testApp {
        startApplication()
        TeacherReminderFixtures.flags("roadmap")
        seedTroubledStudent()
        val client = createJsonClient(this)
        assertEquals(
            listOf(AttentionReasonType.BEHIND, AttentionReasonType.MISMATCH, AttentionReasonType.NOT_WRAPPED_UP, AttentionReasonType.NO_HOMEWORK),
            client.attention(getTeacherToken(client)).single().reasons.map { it.type },
        )
    }

    @Test
    fun `a fixed number of queries whatever the number of students`() = testApp {
        startApplication()
        TeacherReminderFixtures.flags("teacherReminders", "roadmap", "lessonPrep")
        val service: TeacherAttentionService = application.dependencies.resolve()
        val features = AttentionFeatures(roadmap = true, lessonPrep = true)
        seedTroubledStudent()

        fun count(expectedStudents: Int): Int = transaction {
            var statements = 0
            registerInterceptor(object : StatementInterceptor {
                override fun beforeExecution(transaction: Transaction, context: StatementContext) { statements++ }
            })
            assertEquals(expectedStudents, service.compute(TEACHER, now, features).size)
            statements
        }
        val one = count(1)
        assertTrue(one >= 9, "statements are counted ($one)")

        repeat(3) { i ->
            val student = TeacherReminderFixtures.linkedStudent("Kind$i")
            val roadmap = LessonPrepFixtures.roadmap(student)
            LessonPrepFixtures.lesson(now.minusDays(2), student = student)
            LessonPrepFixtures.checkItem(LessonPrepFixtures.items(roadmap.current).first(), LessonPrepFixtures.lesson(now.minusDays(4), student = student))
        }
        assertEquals(one, count(4))
    }

    @Test
    fun `follow-up lessons that are only CONFIRMED count once they are over`() = testApp {
        startApplication()
        TeacherReminderFixtures.flags("teacherReminders", "lessonPrep")
        val past = LessonPrepFixtures.lesson(now.minusDays(1))
        LessonPrepFixtures.plan(past)
        LessonPrepFixtures.homework(past, "Perfekt")
        val running = LessonPrepFixtures.lesson(now.minusMinutes(30), LessonStatus.CONFIRMED)
        val client = createJsonClient(this)
        assertEquals(emptyList(), client.attention(getTeacherToken(client)), "the running lesson $running is not over yet")
    }
}
