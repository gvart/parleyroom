package com.gvart.parleyroom.ai

import com.gvart.parleyroom.ai.data.AiUsageTable
import com.gvart.parleyroom.ai.data.AiUseCase
import com.gvart.parleyroom.ai.data.GenerationJobStatus
import com.gvart.parleyroom.ai.data.GenerationJobTable
import com.gvart.parleyroom.ai.llm.FakeLlmGateway
import com.gvart.parleyroom.ai.transfer.AiSettingsResponse
import com.gvart.parleyroom.ai.transfer.AiUsageResponse
import com.gvart.parleyroom.ai.transfer.AiUseCaseSetting
import com.gvart.parleyroom.ai.transfer.UpdateAiModelRequest
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.practice.PracticeFixtures
import com.gvart.parleyroom.practice.transfer.CreateSentenceRequest
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.vocabulary.data.WordType
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiUsageIntegrationTest : IntegrationTest() {

    private fun usageRows(): List<ResultRow> = transaction {
        AiUsageTable.selectAll().orderBy(AiUsageTable.createdAt, SortOrder.ASC).toList()
    }

    private suspend fun HttpClient.putModel(token: String, useCase: String, model: String): HttpResponse =
        put("/api/v1/admin/ai/settings/$useCase") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody(UpdateAiModelRequest(model))
        }

    @Test
    fun `a job records one priced row per model call`() = testApp(AI_PRICING_CONFIG) {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val job = client.generateAndWait(token, seedLesson())
        assertEquals(GenerationJobStatus.SUCCEEDED, job.status)
        assertEquals("claude-haiku-5-5", job.model)

        val row = usageRows().single()
        assertEquals(TEACHER, row[AiUsageTable.userId].value)
        assertEquals(AiUseCase.GENERATE, row[AiUsageTable.useCase])
        assertEquals("claude-haiku-5-5", row[AiUsageTable.model])
        assertEquals(UUID.fromString(job.id), row[AiUsageTable.jobId]?.value)
        assertTrue(row[AiUsageTable.success])
        assertEquals(job.usage!!.inputTokens, row[AiUsageTable.inputTokens])
        assertEquals(job.usage.outputTokens, row[AiUsageTable.outputTokens])
        val expected = (BigDecimal("0.10") * row[AiUsageTable.inputTokens].toBigDecimal() +
                BigDecimal("0.50") * row[AiUsageTable.outputTokens].toBigDecimal()).divide(BigDecimal(1_000_000), 6, java.math.RoundingMode.HALF_UP)
        assertEquals(expected, row[AiUsageTable.costUsd])
        assertTrue(row[AiUsageTable.costUsd] > BigDecimal.ZERO)
    }

    @Test
    fun `a validation retry is a second row`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val job = client.generateAndWait(token, seedLesson(), "[fake:invalid-once]")
        assertEquals(2, job.attempts)

        val rows = usageRows()
        assertEquals(2, rows.size)
        assertTrue(rows.all { it[AiUsageTable.success] && it[AiUsageTable.jobId]?.value == UUID.fromString(job.id) })
    }

    @Test
    fun `a provider failure is recorded without tokens`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val job = client.generateAndWait(token, seedLesson(), "[fake:error]")
        assertEquals(GenerationJobStatus.FAILED, job.status)

        val row = usageRows().single()
        assertFalse(row[AiUsageTable.success])
        assertEquals("AI_PROVIDER_ERROR", row[AiUsageTable.errorCode])
        assertEquals(0, row[AiUsageTable.inputTokens])
        assertEquals(0, row[AiUsageTable.outputTokens])
        assertEquals(BigDecimal("0.000000"), row[AiUsageTable.costUsd])
    }

    @Test
    fun `sentence feedback is recorded for the student`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)
        val id = PracticeFixtures.word("kümmern", wordType = WordType.VERB)
        val response = client.post("/api/v1/vocabulary/$id/sentences") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody(CreateSentenceRequest("Ich kümmere mich um die Blumen."))
        }
        assertEquals(HttpStatusCode.Created, response.status)

        val row = usageRows().single()
        assertEquals(STUDENT, row[AiUsageTable.userId].value)
        assertEquals(AiUseCase.SENTENCE_FEEDBACK, row[AiUsageTable.useCase])
        assertEquals(null, row[AiUsageTable.jobId])
        assertTrue(row[AiUsageTable.success])
        assertTrue(row[AiUsageTable.inputTokens] > 0)
    }

    @Test
    fun `a changed model applies to the next job`() = testApp(AI_PRICING_CONFIG) {
        val client = createJsonClient(this)
        val admin = getAdminToken(client)
        val teacher = getTeacherToken(client)

        val updated = client.putModel(admin, "GENERATE", "claude-sonnet-5-5")
        assertEquals(HttpStatusCode.OK, updated.status)
        assertEquals("claude-sonnet-5-5", updated.body<AiUseCaseSetting>().model)

        val settings = client.get("/api/v1/admin/ai/settings") { bearerAuth(admin) }.body<AiSettingsResponse>()
        assertEquals(AiUseCase.entries.toList(), settings.useCases.map { it.useCase })
        assertEquals("claude-sonnet-5-5", settings.useCases.single { it.useCase == AiUseCase.GENERATE }.model)
        assertEquals("claude-haiku-5-5", settings.useCases.single { it.useCase == AiUseCase.REFINE }.model)
        assertEquals(listOf("claude-haiku-5-5", "claude-sonnet-5-5"), settings.models.map { it.id })
        assertEquals(2.0, settings.models.single { it.id == "claude-sonnet-5-5" }.inputPerMTok)

        val job = client.generateAndWait(teacher, seedLesson())
        assertEquals("claude-sonnet-5-5", job.model)
        assertEquals("claude-sonnet-5-5", transaction {
            GenerationJobTable.selectAll().where { GenerationJobTable.id eq UUID.fromString(job.id) }.single()[GenerationJobTable.model]
        })
        assertEquals("claude-sonnet-5-5", usageRows().single()[AiUsageTable.model])
        assertEquals("claude-sonnet-5-5", FakeLlmGateway.received.last().model)
    }

    @Test
    fun `only priced models and known use cases can be set`() = testApp(AI_PRICING_CONFIG) {
        val client = createJsonClient(this)
        val admin = getAdminToken(client)

        val unknownModel = client.putModel(admin, "GENERATE", "gpt-5")
        assertEquals(HttpStatusCode.BadRequest, unknownModel.status)
        assertEquals("unknown_model", unknownModel.body<ProblemDetail>().code)

        val unknownUseCase = client.putModel(admin, "SUMMARIZE", "claude-haiku-5-5")
        assertEquals(HttpStatusCode.BadRequest, unknownUseCase.status)
        assertEquals("unknown_use_case", unknownUseCase.body<ProblemDetail>().code)
    }

    @Test
    fun `admin AI endpoints are admin only`() = testApp(AI_PRICING_CONFIG) {
        val client = createJsonClient(this)
        for (token in listOf(getTeacherToken(client), getStudentToken(client))) {
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/admin/ai/settings") { bearerAuth(token) }.status)
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/admin/ai/usage") { bearerAuth(token) }.status)
            assertEquals(HttpStatusCode.Forbidden, client.putModel(token, "GENERATE", "claude-sonnet-5-5").status)
        }
    }

    @Test
    fun `usage is aggregated by day, use case, model and user`() = testApp {
        val client = createJsonClient(this)
        val admin = getAdminToken(client)
        fun at(day: String, hour: Int) = LocalDate.parse(day).atTime(hour, 0).atOffset(ZoneOffset.UTC)
        fun insert(user: UUID, useCase: AiUseCase, model: String, createdAt: OffsetDateTime, cost: String, success: Boolean = true) = transaction {
            AiUsageTable.insert {
                it[userId] = user
                it[AiUsageTable.useCase] = useCase
                it[AiUsageTable.model] = model
                it[AiUsageTable.success] = success
                it[errorCode] = if (success) null else "AI_PROVIDER_ERROR"
                it[inputTokens] = if (success) 100 else 0
                it[outputTokens] = if (success) 10 else 0
                it[cacheReadTokens] = if (success) 1000 else 0
                it[costUsd] = BigDecimal(cost)
                it[AiUsageTable.createdAt] = createdAt
            }
        }
        insert(TEACHER, AiUseCase.GENERATE, "claude-haiku-5-5", at("2026-03-01", 0), "0.010000")
        insert(TEACHER, AiUseCase.GENERATE, "claude-haiku-5-5", at("2026-03-01", 23), "0.000000", success = false)
        insert(TEACHER, AiUseCase.REFINE, "claude-sonnet-5-5", at("2026-03-03", 12), "0.200000")
        insert(STUDENT, AiUseCase.SENTENCE_FEEDBACK, "claude-haiku-5-5", at("2026-03-02", 8), "0.001000")
        // Outside the range on both ends.
        insert(STUDENT, AiUseCase.SENTENCE_FEEDBACK, "claude-haiku-5-5", at("2026-02-28", 23), "1.000000")
        insert(STUDENT, AiUseCase.SENTENCE_FEEDBACK, "claude-haiku-5-5", at("2026-03-04", 0), "1.000000")

        val usage = client.get("/api/v1/admin/ai/usage?from=2026-03-01&to=2026-03-03") { bearerAuth(admin) }.body<AiUsageResponse>()
        assertEquals("2026-03-01", usage.from)
        assertEquals("2026-03-03", usage.to)
        assertEquals(4, usage.totals.calls)
        assertEquals(1, usage.totals.failedCalls)
        assertEquals(300, usage.totals.inputTokens)
        assertEquals(3000, usage.totals.cacheReadTokens)
        assertEquals(0.211, usage.totals.costUsd, 1e-9)

        assertEquals(listOf("2026-03-01", "2026-03-02", "2026-03-03"), usage.byDay.map { it.date })
        assertEquals(listOf(2, 1, 1), usage.byDay.map { it.calls })
        assertEquals(listOf(1, 0, 0), usage.byDay.map { it.failedCalls })

        assertEquals(2, usage.byUseCase.single { it.useCase == AiUseCase.GENERATE }.calls)
        assertEquals(0.2, usage.byModel.first().costUsd, 1e-9)
        assertEquals("claude-sonnet-5-5", usage.byModel.first().model)

        assertEquals(listOf(TEACHER.toString(), STUDENT.toString()), usage.byUser.map { it.userId })
        val teacher = usage.byUser.first()
        assertEquals("teacher@test.com", teacher.email)
        assertEquals(UserRole.TEACHER, teacher.role)
        assertEquals(3, teacher.calls)
        assertEquals(1, teacher.failedCalls)
        assertTrue(teacher.lastUsedAt.isEqual(at("2026-03-03", 12)))

        val empty = client.get("/api/v1/admin/ai/usage?from=2025-01-01&to=2025-01-02") { bearerAuth(admin) }.body<AiUsageResponse>()
        assertEquals(0, empty.totals.calls)
        assertEquals(listOf(0, 0), empty.byDay.map { it.calls })

        val defaults = client.get("/api/v1/admin/ai/usage") { bearerAuth(admin) }.body<AiUsageResponse>()
        assertEquals(30, defaults.byDay.size)
        assertEquals(LocalDate.now(ZoneOffset.UTC).toString(), defaults.to)

        val reversed = client.get("/api/v1/admin/ai/usage?from=2026-03-03&to=2026-03-01") { bearerAuth(admin) }
        assertEquals(HttpStatusCode.BadRequest, reversed.status)
    }
}
