package com.gvart.parleyroom.goal

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.ai.awaitJob
import com.gvart.parleyroom.ai.data.AiUsageTable
import com.gvart.parleyroom.ai.data.AiUseCase
import com.gvart.parleyroom.ai.data.GenerationJobKind
import com.gvart.parleyroom.ai.data.GenerationJobStatus
import com.gvart.parleyroom.ai.llm.FakeLlmGateway
import com.gvart.parleyroom.ai.service.Prompts
import com.gvart.parleyroom.ai.transfer.AiJobSummary
import com.gvart.parleyroom.ai.transfer.AiJobTargetType
import com.gvart.parleyroom.ai.transfer.GenerationJobResponse
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.goal.data.GoalTable
import com.gvart.parleyroom.goal.data.RoadmapItemKind
import com.gvart.parleyroom.goal.transfer.RoadmapJobResult
import com.gvart.parleyroom.goal.transfer.TeacherGoalView
import com.gvart.parleyroom.user.data.UserTable
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoadmapGenerationIntegrationTest : IntegrationTest() {

    private val start = LocalDate.of(2026, 10, 12)
    private val deadline = LocalDate.of(2027, 1, 31)

    private suspend fun HttpClient.startJob(token: String, path: String, json: String = "{}"): HttpResponse = post(path) {
        bearerAuth(token); contentType(ContentType.Application.Json); setBody(json)
    }

    private suspend fun HttpClient.generated(token: String, goal: UUID, json: String = "{}"): GenerationJobResponse {
        val response = startJob(token, "/api/v1/goals/$goal/roadmap/generate", json)
        assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
        return awaitJob(token, response.body<GenerationJobResponse>().id)
    }

    private suspend fun HttpClient.view(token: String, goal: UUID): TeacherGoalView = get("/api/v1/goals/$goal") { bearerAuth(token) }.body()

    private suspend fun HttpResponse.problem(status: HttpStatusCode, code: String) {
        assertEquals(status, this.status, bodyAsText())
        assertEquals(code, body<ProblemDetail>().code)
    }

    private fun roadmapCalls() = FakeLlmGateway.received.filter {
        Prompts.section(it.messages.first().text, "task") in setOf(Prompts.TASK_ROADMAP, Prompts.TASK_MILESTONE_REGEN)
    }

    /** An exam goal whose teacher notes name the student (Test Student). */
    private fun examGoal(): UUID {
        val goal = GoalFixtures.goal(start = start, deadline = deadline)
        transaction {
            GoalTable.update({ GoalTable.id eq goal }) {
                it[exam] = "Goethe Start Deutsch 1"
                it[targetLevel] = LanguageLevel.A1
                it[levelNote] = "Test Student kennt das Alphabet, test ist kein Name"
                it[interests] = "Kochen"
            }
            UserTable.update({ UserTable.id eq com.gvart.parleyroom.ai.STUDENT }) { it[level] = LanguageLevel.A1 }
        }
        return goal
    }

    @Test
    fun `generates a roadmap from the goal fields only`() = testApp {
        startApplication()
        GoalFixtures.enableRoadmap()
        FakeLlmGateway.received.clear()
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val goal = examGoal()

        val job = client.generated(teacher, goal)
        assertEquals(GenerationJobStatus.SUCCEEDED, job.status, job.error?.toString())
        assertEquals(GenerationJobKind.ROADMAP, job.kind)
        assertEquals(goal.toString(), job.goalId)
        assertEquals("claude-sonnet-5-5", job.model)
        val result = Json.decodeFromJsonElement<RoadmapJobResult>(job.result!!)

        val view = client.view(teacher, goal)
        assertNull(view.activeJob)
        // 16 weeks × 2 lessons = 32 lessons → 8 milestones, in order, inside the goal period.
        assertEquals(8, view.milestones.size)
        assertEquals(result.milestoneCount, view.milestones.size)
        assertEquals((1..8).toList(), view.milestones.map { it.position })
        assertEquals(start.toString(), view.milestones.first().startDate)
        assertEquals(deadline.toString(), view.milestones.last().endDate)
        view.milestones.zipWithNext().forEach { (a, b) -> assertTrue(LocalDate.parse(b.startDate) > LocalDate.parse(a.endDate)) }
        assertTrue(view.milestones.all { it.canDo.startsWith("Ich kann") && it.items.size == 3 })
        assertEquals(listOf(RoadmapItemKind.TOPIC, RoadmapItemKind.GRAMMAR, RoadmapItemKind.VOCAB), view.milestones[0].items.map { it.kind })
        assertEquals(0, view.progress.percent)

        // Only goal fields and the CEFR level went to the model: no name, email, and the exam reference is injected.
        val request = roadmapCalls().single().messages.first().text
        assertTrue("Current level: A1" in request)
        assertTrue("Goethe-Zertifikat A1: Start Deutsch 1" in Prompts.section(request, "exam_reference")!!)
        assertEquals("[Name] [Name] kennt das Alphabet, [Name] ist kein Name", Prompts.section(request, "level_note"))
        assertTrue("student@test.com" !in request)
        assertTrue(transaction { AiUsageTable.selectAll().any { it[AiUsageTable.useCase] == AiUseCase.ROADMAP } })

        // The tray names the goal's student.
        val tray = client.get("/api/v1/ai/jobs") { bearerAuth(teacher) }.body<List<AiJobSummary>>().single { it.id == job.id }
        assertEquals(AiJobTargetType.GOAL, tray.targetType)
        assertEquals("Test Student", tray.studentName)
    }

    @Test
    fun `an existing roadmap is replaced only on request`() = testApp {
        startApplication()
        GoalFixtures.enableRoadmap()
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val goal = examGoal()

        client.generated(teacher, goal)
        val before = client.view(teacher, goal).milestones.map { it.id }
        client.startJob(teacher, "/api/v1/goals/$goal/roadmap/generate").problem(HttpStatusCode.Conflict, "ROADMAP_EXISTS")

        val job = client.generated(teacher, goal, """{ "replace": true }""")
        assertEquals(GenerationJobStatus.SUCCEEDED, job.status)
        val after = client.view(teacher, goal).milestones.map { it.id }
        assertEquals(before.size, after.size)
        assertTrue(after.none { it in before })
    }

    @Test
    fun `regenerating one milestone keeps the others, its place and dates`() = testApp {
        startApplication()
        GoalFixtures.enableRoadmap()
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val goal = examGoal()
        client.generated(teacher, goal)
        val before = client.view(teacher, goal).milestones
        val target = before[1]

        val response = client.startJob(teacher, "/api/v1/goals/$goal/milestones/${target.id}/regenerate", """{ "instruction": "Mehr Prüfungstraining" }""")
        assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
        val job = client.awaitJob(teacher, response.body<GenerationJobResponse>().id)
        assertEquals(GenerationJobStatus.SUCCEEDED, job.status, job.error?.toString())
        assertEquals(GenerationJobKind.MILESTONE_REGEN, job.kind)
        assertEquals(target.id, job.input.milestoneId)

        val after = client.view(teacher, goal).milestones
        assertEquals(before.filter { it.id != target.id }, after.filter { it.id != target.id })
        val rewritten = after.single { it.id == target.id }
        assertEquals(target.position, rewritten.position)
        assertEquals(target.startDate to target.endDate, rewritten.startDate to rewritten.endDate)
        assertEquals("Neu: Mehr Prüfungstraining", rewritten.title)
        assertEquals(listOf(RoadmapItemKind.SKILL, RoadmapItemKind.EXAM), rewritten.items.map { it.kind })
        assertTrue(rewritten.items.none { item -> item.id in target.items.map { it.id } })

        val request = roadmapCalls().last().messages.first().text
        assertTrue("[REWRITE] 2. ${target.title}" in Prompts.section(request, "roadmap")!!)

        client.post("/api/v1/goals/$goal/milestones/${before[0].id}/done") { bearerAuth(teacher) }
        client.startJob(teacher, "/api/v1/goals/$goal/milestones/${before[0].id}/regenerate")
            .problem(HttpStatusCode.Conflict, "ROADMAP_MILESTONE_DONE")
    }

    @Test
    fun `invalid output is retried once with the problems`() = testApp {
        startApplication()
        GoalFixtures.enableRoadmap()
        FakeLlmGateway.received.clear()
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val goal = examGoal()

        val job = client.generated(teacher, goal, """{ "instruction": "[fake:invalid-once]" }""")
        assertEquals(GenerationJobStatus.SUCCEEDED, job.status)
        assertEquals(2, job.attempts)
        val retry = roadmapCalls().last().messages.last().text
        assertNotNull(Prompts.section(retry, Prompts.VALIDATION_ERRORS)).also { assertTrue("Ich kann" in it) }

        val failed = client.generated(teacher, GoalFixtures.goal(studentId = com.gvart.parleyroom.ai.STUDENT_2, teacherId = com.gvart.parleyroom.ai.TEACHER),
            """{ "instruction": "[fake:invalid]" }""")
        assertEquals(GenerationJobStatus.FAILED, failed.status)
        assertEquals("AI_OUTPUT_INVALID", failed.error?.code)
        assertNotEquals(0, failed.attempts)
    }

    @Test
    fun `exam references match Goethe and telc names`() {
        fun ref(exam: String?, level: LanguageLevel? = null) = Prompts.examReference(exam, level)?.lineSequence()?.first()
        assertTrue(ref("Goethe Start Deutsch 1")!!.startsWith("Goethe-Zertifikat A1"))
        assertTrue(ref("Goethe-Zertifikat A2")!!.startsWith("Goethe-Zertifikat A2"))
        assertTrue(ref("Goethe", LanguageLevel.B1)!!.startsWith("Goethe-Zertifikat B1"))
        assertTrue(ref("telc Deutsch B1")!!.startsWith("telc Deutsch B1"))
        assertNull(ref("telc Deutsch C1"))
        assertNull(ref("TestDaF", LanguageLevel.B1))
        assertNull(ref(null, LanguageLevel.A1))
    }
}
