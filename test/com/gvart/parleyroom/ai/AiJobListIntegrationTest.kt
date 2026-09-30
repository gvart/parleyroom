package com.gvart.parleyroom.ai

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.ai.data.GenerationJobKind
import com.gvart.parleyroom.ai.data.GenerationJobStatus
import com.gvart.parleyroom.ai.data.GenerationJobTable
import com.gvart.parleyroom.ai.transfer.AiJobEvent
import com.gvart.parleyroom.ai.transfer.AiJobSummary
import com.gvart.parleyroom.ai.transfer.AiJobTargetType
import com.gvart.parleyroom.ai.transfer.DraftBundleResponse
import com.gvart.parleyroom.ai.transfer.DocumentDraftResponse
import com.gvart.parleyroom.ai.transfer.DraftKind
import com.gvart.parleyroom.ai.transfer.GenerationJobResponse
import com.gvart.parleyroom.ai.transfer.RefineRequest
import com.gvart.parleyroom.library.LibraryFixtures
import com.gvart.parleyroom.notification.service.NotificationSseManager
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.plugins.di.dependencies
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AiJobListIntegrationTest : IntegrationTest() {

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun HttpClient.jobs(token: String, query: String = ""): List<AiJobSummary> =
        get("/api/v1/ai/jobs$query") { bearerAuth(token) }.body()

    @Test
    fun `the tray lists a running generate job by lesson and student, then as recently finished`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val lessonId = seedLesson()
        val bundle = client.startGenerate(token, lessonId, "[fake:delay=1500]").body<DraftBundleResponse>()

        val running = client.jobs(token, "?active=true").single()
        assertEquals(bundle.job!!.id, running.id)
        assertEquals(GenerationJobKind.GENERATE, running.kind)
        assertTrue(running.status == GenerationJobStatus.QUEUED || running.status == GenerationJobStatus.RUNNING)
        assertEquals(AiJobTargetType.LESSON, running.targetType)
        assertEquals(bundle.id, running.bundleId)
        assertEquals(lessonId.toString(), running.lessonId)
        assertEquals("Lektion", running.lessonTitle)
        assertEquals(STUDENT.toString(), running.studentId)
        assertEquals("Test Student", running.studentName)
        assertEquals(DraftKind.entries, running.draftKinds)

        client.awaitJob(token, running.id)
        assertEquals(emptyList(), client.jobs(token, "?active=true"))
        val finished = client.jobs(token).single()
        assertEquals(GenerationJobStatus.SUCCEEDED, finished.status)
        assertNotNull(finished.finishedAt)

        // Jobs finished more than a day ago leave the tray.
        transaction {
            GenerationJobTable.update({ GenerationJobTable.id eq UUID.fromString(finished.id) }) {
                it[finishedAt] = OffsetDateTime.now().minusDays(2)
            }
        }
        assertEquals(emptyList(), client.jobs(token))
    }

    @Test
    fun `a document refine is named after the document`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val documentId = LibraryFixtures.document("Urlaub am Meer")

        val job = client.post("/api/v1/documents/$documentId/ai-refine") {
            contentType(ContentType.Application.Json); bearerAuth(token); setBody(RefineRequest("Mehr Beispiele"))
        }.body<GenerationJobResponse>()
        client.awaitJob(token, job.id)

        val summary = client.jobs(token).single()
        assertEquals(GenerationJobKind.REFINE, summary.kind)
        assertEquals(AiJobTargetType.DOCUMENT, summary.targetType)
        assertEquals(documentId.toString(), summary.documentId)
        assertEquals("Urlaub am Meer", summary.documentTitle)
    }

    @Test
    fun `optional draft lookup answers 204 instead of 404 when there is no draft`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val documentId = LibraryFixtures.document("Ohne Entwurf")

        assertEquals(HttpStatusCode.NoContent, client.get("/api/v1/documents/$documentId/draft?optional=true") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/documents/$documentId/draft") { bearerAuth(token) }.status)

        val job = client.post("/api/v1/documents/$documentId/ai-refine") {
            contentType(ContentType.Application.Json); bearerAuth(token); setBody(RefineRequest("Mehr Beispiele"))
        }.body<GenerationJobResponse>()
        client.awaitJob(token, job.id)
        val found = client.get("/api/v1/documents/$documentId/draft?optional=true") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, found.status)
        assertEquals(documentId.toString(), found.body<DocumentDraftResponse>().documentId)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/documents/$documentId/draft?optional=true") { bearerAuth(getStudentToken(client)) }.status)
    }

    @Test
    fun `students cannot list AI jobs`() = testApp {
        val client = createJsonClient(this)
        val response = client.get("/api/v1/ai/jobs") { bearerAuth(getStudentToken(client)) }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `queued and finished jobs are pushed on the teacher's notification stream`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val sse = application.dependencies.resolve<NotificationSseManager>()
        val flow = sse.subscribe(TEACHER)
        try {
            val (jobId, events) = coroutineScope {
                // Undispatched: collecting starts before the job is queued (the stream does not replay).
                val events = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(10_000) { flow.take(2).toList().map { json.decodeFromString<AiJobEvent>(it) } }
                }
                client.startGenerate(token, seedLesson(), "[fake:error]").body<DraftBundleResponse>().job!!.id to events.await()
            }

            assertEquals(listOf(AiJobEvent.STARTED, AiJobEvent.FINISHED), events.map { it.type })
            assertEquals(GenerationJobStatus.QUEUED, events.first().job.status)
            val finished = events.last().job
            assertEquals(jobId, finished.id)
            assertEquals(GenerationJobStatus.FAILED, finished.status)
            assertNotNull(finished.errorCode)
            assertEquals("Lektion", finished.lessonTitle)
        } finally {
            sse.unsubscribe(TEACHER, flow)
        }
    }
}
