package com.gvart.parleyroom.ai

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.library.LibraryFixtures
import com.gvart.parleyroom.ai.data.GenerationJobKind
import com.gvart.parleyroom.ai.data.GenerationJobStatus
import com.gvart.parleyroom.ai.llm.FakeLlmGateway
import com.gvart.parleyroom.ai.transfer.FillMissingRequest
import com.gvart.parleyroom.ai.transfer.FillTranslationsResult
import com.gvart.parleyroom.ai.transfer.GenerationJobResponse
import com.gvart.parleyroom.ai.transfer.MissingFieldsResponse
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.vocabulary.transfer.VocabEntryResponse
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FillTranslationsIntegrationTest : IntegrationTest() {

    private suspend fun HttpClient.entry(token: String, body: String): VocabEntryResponse = post("/api/v1/vocab-entries") {
        contentType(ContentType.Application.Json); bearerAuth(token); setBody(body)
    }.body()

    private suspend fun HttpClient.fill(token: String, body: Any): HttpResponse = post("/api/v1/vocab-entries/fill-missing") {
        contentType(ContentType.Application.Json); bearerAuth(token); setBody(body)
    }

    @Test
    fun `missing fields are listed per student and filled without overwriting`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val haus = client.entry(token, """{ "lemma": "Haus", "article": "DAS", "wordType": "NOUN", "translations": { "en": "house" } }""")
        val gehen = client.entry(token, """{ "lemma": "gehen", "wordType": "VERB", "translations": { "ru": "идти", "en": "to go" },
            "explanationDe": "sich zu Fuß bewegen" }""")
        val baum = client.entry(token, """{ "lemma": "Baum", "article": "DER", "wordType": "NOUN" }""")
        listOf(haus, gehen).forEach { LibraryFixtures.assign(UUID.fromString(STUDENT_ID), UUID.fromString(it.id)) }

        val missing = client.get("/api/v1/students/$STUDENT_ID/vocab/missing-fields?fields=ru,de_explanation") { bearerAuth(token) }
            .body<MissingFieldsResponse>()
        assertEquals(1, missing.count, "Baum is not the student's word; gehen is complete")
        assertEquals(listOf(haus.id), missing.entryIds)

        FakeLlmGateway.received.clear()
        val started = client.fill(token, FillMissingRequest(listOf(haus.id, gehen.id, baum.id), listOf("ru", "de_explanation")))
        assertEquals(HttpStatusCode.Accepted, started.status)
        val job = client.awaitJob(token, started.body<GenerationJobResponse>().id)
        assertEquals(GenerationJobStatus.SUCCEEDED, job.status)
        assertEquals(GenerationJobKind.FILL_TRANSLATIONS, job.kind)
        assertNull(job.lessonId)
        val result = Json.decodeFromJsonElement<FillTranslationsResult>(job.result!!)
        assertEquals(listOf(gehen.id), result.skipped)
        assertEquals(setOf(haus.id, baum.id), result.updated.map { it.entryId }.toSet())

        val filled = client.get("/api/v1/vocab-entries/${haus.id}") { bearerAuth(token) }.body<VocabEntryResponse>()
        assertEquals("Haus (ru)", filled.translations["ru"])
        assertEquals("house", filled.translations["en"], "existing fields are kept")
        assertEquals("Erklärung: Haus", filled.explanationDe)
        val untouched = client.get("/api/v1/vocab-entries/${gehen.id}") { bearerAuth(token) }.body<VocabEntryResponse>()
        assertEquals("идти", untouched.translations["ru"])

        // Only the words themselves go to the model.
        val sent = FakeLlmGateway.received.single().messages.single().text
        assertTrue("Haus" in sent && "Baum" in sent)
        assertFalse("gehen" in sent)
        assertFalse(STUDENT_ID in sent || haus.id in sent)

        val after = client.get("/api/v1/students/$STUDENT_ID/vocab/missing-fields?fields=ru,de_explanation") { bearerAuth(token) }
            .body<MissingFieldsResponse>()
        assertEquals(0, after.count)
    }

    @Test
    fun `fill-missing validates its input`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val haus = client.entry(token, """{ "lemma": "Haus", "article": "DAS", "wordType": "NOUN" }""")

        val empty = client.fill(token, """{ "entryIds": [], "fields": ["ru"] }""")
        assertEquals("VALIDATION_FAILED", empty.body<ProblemDetail>().code)
        val badField = client.fill(token, """{ "entryIds": ["${haus.id}"], "fields": ["fr"] }""")
        assertEquals("VOCAB_DISPLAY_FIELD_UNSUPPORTED", badField.body<ProblemDetail>().code)
        val foreign = client.fill(token, """{ "entryIds": ["${UUID.randomUUID()}"], "fields": ["ru"] }""")
        assertEquals("VOCAB_ENTRY_NOT_FOUND", foreign.body<ProblemDetail>().code)
        assertEquals(HttpStatusCode.Forbidden, client.fill(getStudentToken(client), FillMissingRequest(listOf(haus.id), listOf("ru"))).status)

        val noFields = client.get("/api/v1/students/$STUDENT_ID/vocab/missing-fields") { bearerAuth(token) }
        assertEquals(HttpStatusCode.BadRequest, noFields.status)
        val notMyStudent = client.get("/api/v1/students/$STUDENT_2_ID/vocab/missing-fields?fields=ru") { bearerAuth(token) }
        assertEquals(HttpStatusCode.Forbidden, notMyStudent.status)
    }

    @Test
    fun `fill-missing needs a configured provider`() = testApp(mapOf("ai.provider" to "anthropic")) {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val haus = client.entry(token, """{ "lemma": "Haus", "article": "DAS", "wordType": "NOUN" }""")
        val response = client.fill(token, FillMissingRequest(listOf(haus.id), listOf("ru")))
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("AI_NOT_CONFIGURED", response.body<ProblemDetail>().code)
    }
}
