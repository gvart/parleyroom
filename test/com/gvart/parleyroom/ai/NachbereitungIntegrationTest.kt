package com.gvart.parleyroom.ai

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.ai.config.AiConfig
import com.gvart.parleyroom.ai.service.GenerationJobRunner
import com.gvart.parleyroom.topic.transfer.CreateTopicRequest
import com.gvart.parleyroom.topic.transfer.GrammarTopicRequest
import com.gvart.parleyroom.vocabulary.seedStudentVocab
import kotlinx.coroutines.delay
import java.time.OffsetDateTime
import kotlin.time.Duration
import com.gvart.parleyroom.ai.data.GenerationJobKind
import com.gvart.parleyroom.ai.data.GenerationJobStatus
import com.gvart.parleyroom.ai.llm.FakeLlmGateway
import com.gvart.parleyroom.ai.transfer.GenerationJobResponse
import com.gvart.parleyroom.ai.transfer.NachbereitungMode
import com.gvart.parleyroom.ai.transfer.NachbereitungResult
import com.gvart.parleyroom.ai.transfer.NachbereitungState
import com.gvart.parleyroom.ai.transfer.PublishGrammarTopic
import com.gvart.parleyroom.ai.transfer.PublishRequest
import com.gvart.parleyroom.ai.transfer.PublishResponse
import com.gvart.parleyroom.ai.transfer.PublishTopic
import com.gvart.parleyroom.ai.transfer.PublishVocabItem
import com.gvart.parleyroom.ai.transfer.RefineRequest
import com.gvart.parleyroom.ai.transfer.ReviewUpdateRequest
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.document.data.DocumentVersionReason
import com.gvart.parleyroom.document.service.DocumentBlockValidator
import com.gvart.parleyroom.document.transfer.DocumentResponse
import com.gvart.parleyroom.document.transfer.DocumentVersionSummary
import com.gvart.parleyroom.document.transfer.UpdateDocumentRequest
import com.gvart.parleyroom.group.data.GroupType
import com.gvart.parleyroom.group.transfer.GroupRequest
import com.gvart.parleyroom.group.transfer.GroupResponse
import com.gvart.parleyroom.lesson.transfer.CorrectedSentenceInput
import com.gvart.parleyroom.lesson.transfer.LessonResponse
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.vocabulary.transfer.StudentVocabPageResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NachbereitungIntegrationTest : IntegrationTest() {

    private val json = Json { ignoreUnknownKeys = true }

    private fun GenerationJobResponse.nachbereitung(): NachbereitungResult = json.decodeFromJsonElement(result!!)

    private suspend fun HttpClient.document(token: String, id: String): HttpResponse =
        get("/api/v1/documents/$id") { bearerAuth(token) }

    private suspend fun HttpClient.publish(token: String, lessonId: UUID, request: PublishRequest): HttpResponse =
        post("/api/v1/lessons/$lessonId/nachbereitung/publish") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(request)
        }

    /** Publishes every word and accepts every new topic / grammar proposal. */
    private fun publishAll(job: GenerationJobResponse, result: NachbereitungResult = job.nachbereitung()) = PublishRequest(
        jobId = job.id,
        vocab = result.vocab.map { PublishVocabItem(it.key, entry = it.entry, topicKeys = listOfNotNull(it.topicKey)) },
        topics = result.topics.filter { it.existingId == null }.map { PublishTopic(it.key, it.name) },
        grammarTopics = result.grammarTopics.filter { it.existingId == null }.map { PublishGrammarTopic(it.key, it.name, it.level) },
        correctedSentences = listOf(CorrectedSentenceInput("Darum muss du dicht kümmern", "Darum musst du dich kümmern")),
    )

    @Test
    fun `generate creates an unshared draft from validated output`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val lessonId = seedLesson(level = LanguageLevel.B1)

        val started = client.startGenerate(token, lessonId, prompt = "Mach eine Vokabelliste.")
        assertEquals(HttpStatusCode.Accepted, started.status)
        assertEquals(GenerationJobKind.GENERATE, started.body<GenerationJobResponse>().kind)

        val job = client.awaitJob(token, started.body<GenerationJobResponse>().id)
        assertEquals(GenerationJobStatus.SUCCEEDED, job.status)
        assertEquals(1, job.attempts)
        assertEquals("fake", job.model)
        assertNotNull(job.usage?.inputTokens)
        val result = job.nachbereitung()
        assertEquals(job.documentId, result.documentId)
        assertEquals(listOf("Vorbeikommen", "Gießkanne", "Blumen gießen", "Teekanne", "kümmern", "Darum muss du dicht kümmern"),
            result.vocab.map { it.entry.lemma })
        val gieskanne = result.vocab.single { it.entry.lemma == "Gießkanne" }
        assertEquals("DIE", gieskanne.entry.article?.name)
        assertEquals(lessonId.toString(), gieskanne.entry.sourceLessonId)
        assertTrue(result.vocab.all { it.selected && it.matchedEntryId == null })
        assertEquals("t1", gieskanne.topicKey)
        assertEquals(listOf("Alltag"), result.topics.map { it.name })
        assertEquals(listOf("Perfekt"), result.grammarTopics.map { it.name })

        val document = client.document(token, result.documentId).body<DocumentResponse>()
        assertEquals(lessonId.toString(), document.createdFromLessonId)
        assertTrue(document.lessonIds.isEmpty(), "the draft is not linked to the lesson before publish")
        assertTrue(document.studentIds.isEmpty())
        assertEquals(LanguageLevel.B1, document.level)
        assertTrue(DocumentBlockValidator.completenessIssues(JsonArray(document.blocks)).isEmpty())
        val table = document.blocks.map { it.jsonObject }.single { it["type"]!!.jsonPrimitive.content == "vocab_table" }
        assertEquals(result.vocabTables.single().blockId, table["id"]!!.jsonPrimitive.content)
        assertTrue(table["rows"]!!.jsonArray.isEmpty())

        // The student cannot see the draft.
        assertEquals(HttpStatusCode.NotFound, client.document(getStudentToken(client), result.documentId).status)
        val lessonDocs = client.get("/api/v1/lessons/$lessonId/documents") { bearerAuth(getStudentToken(client)) }
        assertEquals("[]", lessonDocs.body<JsonArray>().toString())
    }

    @Test
    fun `the model never sees names, e-mails or ids`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        transaction {
            UserTable.update({ UserTable.id eq STUDENT }) { it[firstName] = "Olga"; it[lastName] = "Petrowa"; it[level] = LanguageLevel.A2 }
        }
        val earlier = seedLesson(scheduledAt = OffsetDateTime.now().minusDays(7))
        val lessonId = seedLesson()
        // Some library + history so every context section is filled.
        val topic = client.post("/api/v1/topics") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody(CreateTopicRequest(name = "Haushalt"))
        }.body<JsonObject>()["id"]!!.jsonPrimitive.content
        val grammar = client.post("/api/v1/grammar-topics") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody(GrammarTopicRequest(name = "Reflexive Verben", level = LanguageLevel.A2))
        }.body<JsonObject>()["id"]!!.jsonPrimitive.content
        client.patch("/api/v1/lessons/$earlier/content", token, buildJsonObject {
            put("grammarTopicIds", JsonArray(listOf(JsonPrimitive(grammar))))
            put("topicIds", JsonArray(listOf(JsonPrimitive(topic))))
        })
        seedStudentVocab(STUDENT, lemma = "Tisch")

        FakeLlmGateway.received.clear()
        val job = client.generateAndWait(token, lessonId)
        assertEquals(GenerationJobStatus.SUCCEEDED, job.status)

        val call = FakeLlmGateway.received.single()
        val sent = call.system + call.messages.joinToString("\n") { it.text }
        assertTrue("Tisch" in sent, "known words are sent")
        assertTrue("Reflexive Verben (A2)" in sent, "covered grammar is sent")
        assertTrue("Haushalt" in sent, "library topics are sent")
        assertTrue("Level: A2" in sent)
        listOf("Olga", "Petrowa", "Teacher", "@test.com", "student@", STUDENT_ID, TEACHER_ID, lessonId.toString(), topic, grammar)
            .forEach { assertFalse(it in sent, "'$it' must not be sent to the model") }
        val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        // The block schema's own examples contain no uuids either; any uuid would be a leak.
        assertNull(uuid.find(call.messages.joinToString("\n") { it.text }), "no uuid in the user message")
    }

    @Test
    fun `invalid output is retried once with the problems fed back`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        FakeLlmGateway.received.clear()

        val job = client.generateAndWait(token, seedLesson(), prompt = "[fake:invalid-once]")
        assertEquals(GenerationJobStatus.SUCCEEDED, job.status)
        assertEquals(2, job.attempts)
        assertEquals(2, FakeLlmGateway.received.size, "two model calls")
        val retry = FakeLlmGateway.received.last().messages
        assertEquals(3, retry.size, "request, rejected answer, problems")
        assertTrue("<validation_errors>" in retry.last().text)
        assertTrue("text has no gap" in retry.last().text)
    }

    @Test
    fun `output invalid twice fails without a draft and without model text`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val lessonId = seedLesson()

        val job = client.generateAndWait(token, lessonId, prompt = "[fake:invalid]")
        assertEquals(GenerationJobStatus.FAILED, job.status)
        assertEquals("AI_OUTPUT_INVALID", job.error?.code)
        assertEquals(2, job.attempts)
        assertNull(job.result)
        assertNull(job.documentId)
        assertFalse("Heute lernen" in job.error!!.message)

        val state = client.get("/api/v1/lessons/$lessonId/nachbereitung") { bearerAuth(token) }.body<NachbereitungState>()
        assertNull(state.draftDocumentId)
        assertEquals(GenerationJobStatus.FAILED, state.latestJob?.status)
    }

    @Test
    fun `provider errors fail the job with a stable code`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        assertEquals("AI_PROVIDER_ERROR", client.generateAndWait(token, seedLesson(), "[fake:error]").error?.code)
        assertEquals("AI_RATE_LIMITED", client.generateAndWait(token, seedLesson(), "[fake:rate-limit]").error?.code)
    }

    @Test
    fun `without a provider key job starts return 503 and the app still boots`() =
        testApp(mapOf("ai.provider" to "anthropic", "ai.anthropic_api_key" to "")) {
            val client = createJsonClient(this)
            val token = getTeacherToken(client)
            val lessonId = seedLesson()

            val response = client.startGenerate(token, lessonId)
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("AI_NOT_CONFIGURED", response.body<ProblemDetail>().code)
            val state = client.get("/api/v1/lessons/$lessonId/nachbereitung") { bearerAuth(token) }.body<NachbereitungState>()
            assertFalse(state.aiAvailable)
        }

    @Test
    fun `a teacher can have two active jobs, the third is rate limited`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val lessonId = seedLesson()

        val first = client.startGenerate(token, lessonId, "[fake:delay=1500]").body<GenerationJobResponse>()
        val second = client.startGenerate(token, lessonId, "[fake:delay=1500]").body<GenerationJobResponse>()
        val third = client.startGenerate(token, lessonId, "[fake:delay=1500]")
        assertEquals(HttpStatusCode.TooManyRequests, third.status)
        assertEquals("AI_RATE_LIMITED", third.body<ProblemDetail>().code)

        assertEquals(GenerationJobStatus.SUCCEEDED, client.awaitJob(token, first.id).status)
        assertEquals(GenerationJobStatus.SUCCEEDED, client.awaitJob(token, second.id).status)
        assertEquals(HttpStatusCode.Accepted, client.startGenerate(token, lessonId).status)
    }

    @Test
    fun `beyond the global cap jobs wait queued`() = testApp(mapOf("ai.max_concurrent_jobs" to "1")) {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val lessonId = seedLesson()

        val first = client.startGenerate(token, lessonId, "[fake:delay=1500]").body<GenerationJobResponse>()
        val second = client.startGenerate(token, lessonId, "[fake:delay=100]").body<GenerationJobResponse>()
        delay(500)
        assertEquals(GenerationJobStatus.RUNNING, client.get("/api/v1/ai/jobs/${first.id}") { bearerAuth(token) }.body<GenerationJobResponse>().status)
        assertEquals(GenerationJobStatus.QUEUED, client.get("/api/v1/ai/jobs/${second.id}") { bearerAuth(token) }.body<GenerationJobResponse>().status)
        assertEquals(GenerationJobStatus.SUCCEEDED, client.awaitJob(token, second.id).status)
    }

    @Test
    fun `interrupted jobs are failed on startup`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val job = client.startGenerate(token, seedLesson(), "[fake:delay=3000]").body<GenerationJobResponse>()

        // Simulates the next boot: a fresh runner fails what the previous process left behind.
        val runner = GenerationJobRunner(
            AiConfig("fake", "fake", "", 2, 3, Duration.parse("30s")),
        )
        assertEquals(1, runner.failInterruptedJobs())
        val failed = client.get("/api/v1/ai/jobs/${job.id}") { bearerAuth(token) }.body<GenerationJobResponse>()
        assertEquals(GenerationJobStatus.FAILED, failed.status)
        assertEquals("AI_INTERRUPTED", failed.error?.code)
    }

    @Test
    fun `generate validates the lesson and the body`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val noStudent = client.startGenerate(token, seedLesson(students = emptyList()))
        assertEquals(HttpStatusCode.BadRequest, noStudent.status)
        assertEquals("NACHBEREITUNG_NO_ATTENDEES", noStudent.body<ProblemDetail>().code)

        val lessonId = seedLesson()
        val blankNotes = client.post("/api/v1/lessons/$lessonId/nachbereitung/generate") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody("""{ "notes": "   ", "prompt": "x" }""")
        }
        assertEquals(HttpStatusCode.BadRequest, blankNotes.status)
        assertEquals("VALIDATION_FAILED", blankNotes.body<ProblemDetail>().code)

        val missingNotes = client.post("/api/v1/lessons/$lessonId/nachbereitung/generate") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody("""{ "prompt": "x" }""")
        }
        assertEquals(HttpStatusCode.BadRequest, missingNotes.status)

        val unknownTemplate = client.post("/api/v1/lessons/$lessonId/nachbereitung/generate") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody("""{ "notes": "Haus", "prompt": "", "promptTemplateId": "${UUID.randomUUID()}" }""")
        }
        assertEquals("PROMPT_TEMPLATE_NOT_FOUND", unknownTemplate.body<ProblemDetail>().code)

        val asStudent = client.startGenerate(getStudentToken(client), lessonId)
        assertEquals(HttpStatusCode.Forbidden, asStudent.status)
    }

    @Test
    fun `jobs are private to their teacher`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val job = client.generateAndWait(token, seedLesson())

        val asStudent = client.get("/api/v1/ai/jobs/${job.id}") { bearerAuth(getStudentToken(client)) }
        assertEquals(HttpStatusCode.NotFound, asStudent.status)
        assertEquals("AI_JOB_NOT_FOUND", asStudent.body<ProblemDetail>().code)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/ai/jobs/${job.id}") { bearerAuth(getAdminToken(client)) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/ai/jobs/${UUID.randomUUID()}") { bearerAuth(token) }.status)
    }

    @Test
    fun `state shows context, prefilled notes and the latest job`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val lessonId = seedLesson(level = LanguageLevel.B2)
        client.patch("/api/v1/lessons/$lessonId/content", token, buildJsonObject { put("rawNotes", "Notizen aus der Stunde") })
        client.put("/api/v1/lessons/$lessonId/vocab-display") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody("""{ "fields": ["ru", "de_explanation"], "allowTranslationToggle": false }""")
        }

        val before = client.get("/api/v1/lessons/$lessonId/nachbereitung") { bearerAuth(token) }.body<NachbereitungState>()
        assertEquals(NachbereitungMode.ONE_ON_ONE, before.mode)
        assertTrue(before.aiAvailable)
        assertEquals("Notizen aus der Stunde", before.notes)
        assertEquals(listOf("ru", "de_explanation"), before.context.display.fields)
        assertTrue(before.context.lessonOverrideActive)
        assertEquals(1, before.context.attendeeCount)
        assertEquals("Student", before.context.attendees.single().lastName)
        assertNull(before.latestJob)

        val job = client.generateAndWait(token, lessonId)
        val after = client.get("/api/v1/lessons/$lessonId/nachbereitung") { bearerAuth(token) }.body<NachbereitungState>()
        assertEquals(job.id, after.latestJob?.id)
        assertEquals(job.documentId, after.draftDocumentId)
    }

    @Test
    fun `state prefills the notes saved in the live classroom and shows a running job`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val lessonId = seedLesson()
        client.patch("/api/v1/lessons/$lessonId/content") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody("""{ "rawNotes": "die Gießkanne\nBlumen gießen" }""")
        }
        val state = client.get("/api/v1/lessons/$lessonId/nachbereitung") { bearerAuth(token) }.body<NachbereitungState>()
        assertEquals("die Gießkanne\nBlumen gießen", state.notes)

        val running = client.startGenerate(token, lessonId, "[fake:delay=1500]", notes = state.notes!!).body<GenerationJobResponse>()
        val during = client.get("/api/v1/lessons/$lessonId/nachbereitung") { bearerAuth(token) }.body<NachbereitungState>()
        assertEquals(running.id, during.latestJob?.id)
        assertTrue(during.latestJob!!.status in setOf(GenerationJobStatus.QUEUED, GenerationJobStatus.RUNNING))
        assertEquals(listOf("Gießkanne", "Blumen gießen"), client.awaitJob(token, running.id).nachbereitung().vocab.map { it.entry.lemma })
    }

    @Test
    fun `review edits are persisted on the job`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val job = client.generateAndWait(token, seedLesson())
        val result = job.nachbereitung()
        val edited = result.vocab.map {
            if (it.key == "v1") it.copy(selected = false, entry = it.entry.copy(translations = mapOf("ru" to "зайти"))) else it
        }

        val response = client.put("/api/v1/ai/jobs/${job.id}/review") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody(ReviewUpdateRequest(edited))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val saved = client.awaitJob(token, job.id).nachbereitung().vocab.single { it.key == "v1" }
        assertFalse(saved.selected)
        assertEquals("зайти", saved.entry.translations["ru"])

        val unknown = client.put("/api/v1/ai/jobs/${job.id}/review") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody("""{ "vocab": [{ "key": "nope", "entry": { "lemma": "x", "wordType": "NOUN" } }] }""")
        }
        assertEquals(HttpStatusCode.BadRequest, unknown.status)
    }

    @Test
    fun `refine keeps manual edits, snapshots and replaces the draft`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val lessonId = seedLesson()
        val job = client.generateAndWait(token, lessonId)
        val documentId = job.documentId!!

        // Anna edits the draft in the editor.
        val draft = client.document(token, documentId).body<DocumentResponse>()
        val manual = buildJsonObject {
            put("id", UUID.randomUUID().toString()); put("type", "heading"); put("interactive", false)
            put("level", 2); put("text", "Meine Ergänzung")
        }
        client.put("/api/v1/documents/$documentId") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody(UpdateDocumentRequest(draft.title, draft.level, draft.topicIds, draft.grammarTopicIds, draft.audience,
                JsonArray(draft.blocks + manual), draft.revision))
        }
        val edited = client.document(token, documentId).body<DocumentResponse>()

        val started = client.post("/api/v1/ai/jobs/${job.id}/refine") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody(RefineRequest("Mach Übung 2 leichter."))
        }
        assertEquals(HttpStatusCode.Accepted, started.status)
        val refined = client.awaitJob(token, started.body<GenerationJobResponse>().id)
        assertEquals(GenerationJobStatus.SUCCEEDED, refined.status)
        assertEquals(GenerationJobKind.REFINE, refined.kind)
        assertEquals(job.id, refined.parentJobId)
        assertEquals(documentId, refined.documentId)
        assertEquals("Mach Übung 2 leichter.", refined.input.instruction)
        assertEquals(job.input.notes, refined.input.notes)

        val after = client.document(token, documentId).body<DocumentResponse>()
        assertEquals(edited.revision + 1, after.revision)
        assertTrue(after.title.endsWith("(überarbeitet)"))
        val texts = after.blocks.toString()
        assertTrue("Meine Ergänzung" in texts, "manual edits survive the refine")
        assertTrue("Überarbeitet: Mach Übung 2 leichter." in texts)
        assertEquals(refined.nachbereitung().vocabTables.single().blockId,
            after.blocks.map { it.jsonObject }.single { it["type"]!!.jsonPrimitive.content == "vocab_table" }["id"]!!.jsonPrimitive.content)

        val versions = client.get("/api/v1/documents/$documentId/versions") { bearerAuth(token) }.body<List<DocumentVersionSummary>>()
        assertEquals(DocumentVersionReason.AI_REFINE, versions.first().reason)

        // An editor still on the old revision gets a conflict and reloads.
        val stale = client.put("/api/v1/documents/$documentId") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody(UpdateDocumentRequest(edited.title, edited.level, edited.topicIds, edited.grammarTopicIds, edited.audience,
                JsonArray(edited.blocks), edited.revision))
        }
        assertEquals(HttpStatusCode.Conflict, stale.status)
    }

    @Test
    fun `refine after publish keeps the published words and republishing adds no duplicates`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val lessonId = seedLesson()
        val job = client.generateAndWait(token, lessonId)
        val request = publishAll(job)
        val first = client.publish(token, lessonId, request).body<PublishResponse>()

        val refined = client.awaitJob(token, client.post("/api/v1/ai/jobs/${job.id}/refine") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody(RefineRequest("Noch eine Übung."))
        }.body<GenerationJobResponse>().id)
        assertEquals(GenerationJobStatus.SUCCEEDED, refined.status)
        val result = refined.nachbereitung()
        // Published words are now library words: recognized and already with the student.
        assertTrue(result.vocab.all { it.matchedEntryId != null && it.alreadyAssigned })
        val rows = { client: HttpClient -> suspend {
            client.document(token, result.documentId).body<DocumentResponse>().blocks.map { it.jsonObject }
                .single { it["type"]!!.jsonPrimitive.content == "vocab_table" }["rows"]!!.jsonArray
        } }
        assertEquals(first.vocab.map { it.entryId }.toSet(), rows(client)().map { it.jsonObject["vocabEntryId"]!!.jsonPrimitive.content }.toSet(),
            "published rows survive the refine")

        val again = client.publish(token, lessonId, PublishRequest(
            jobId = refined.id,
            vocab = result.vocab.map { PublishVocabItem(it.key, matchedEntryId = it.matchedEntryId) },
        )).body<PublishResponse>()
        assertEquals(0, again.wordsCreated)
        assertEquals(0, again.wordsAssigned)
        assertEquals(first.vocab.map { it.entryId }.toSet(), rows(client)().map { it.jsonObject["vocabEntryId"]!!.jsonPrimitive.content }.toSet())
    }

    @Test
    fun `refine needs a succeeded job`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val failed = client.generateAndWait(token, seedLesson(), "[fake:invalid]")
        val response = client.post("/api/v1/ai/jobs/${failed.id}/refine") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody("""{ "instruction": "leichter" }""")
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("AI_JOB_NOT_READY", response.body<ProblemDetail>().code)
    }

    @Test
    fun `publish puts words to the student and the library, shares the document, and is idempotent`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val studentToken = getStudentToken(client)
        val lessonId = seedLesson()
        val job = client.generateAndWait(token, lessonId)
        val result = job.nachbereitung()
        val request = publishAll(job).let { it.copy(vocab = it.vocab.filter { item -> item.key != "v6" }) }

        val first = client.publish(token, lessonId, request)
        assertEquals(HttpStatusCode.OK, first.status)
        val published = first.body<PublishResponse>()
        assertEquals(5, published.wordsCreated)
        assertEquals(0, published.wordsReused)
        assertEquals(5, published.wordsAssigned)
        assertEquals(1, published.recipients)
        assertEquals(listOf(STUDENT_ID), published.recipientIds)
        assertEquals(1, published.topicsCreated)
        assertEquals(1, published.grammarTopicsCreated)

        // Words: in the student's vocabulary, from this lesson, tagged with the accepted topic.
        val words = client.get("/api/v1/vocabulary?lessonId=$lessonId") { bearerAuth(studentToken) }.body<StudentVocabPageResponse>()
        assertEquals(5, words.words.size)
        val topicId = published.topics.single().id
        assertTrue(words.words.all { topicId in it.topicIds })

        // Lesson content.
        val lesson = client.get("/api/v1/lessons/$lessonId") { bearerAuth(token) }.body<LessonResponse>()
        assertEquals(ANNA_NOTES, lesson.rawNotes)
        assertEquals(listOf("Alltag"), lesson.topics.map { it.name })
        assertEquals(listOf("Perfekt"), lesson.grammarTopics.map { it.name })
        assertEquals(5, lesson.vocab.size)
        assertEquals("Darum musst du dich kümmern", lesson.correctedSentences.single().correct)

        // Document: rows filled for the published words only, shared, linked, readable by the student.
        val document = client.document(studentToken, result.documentId).body<DocumentResponse>()
        val rows = document.blocks.map { it.jsonObject }.single { it["type"]!!.jsonPrimitive.content == "vocab_table" }["rows"]!!.jsonArray
        assertEquals(5, rows.size)
        assertEquals(5, document.vocab.size)
        assertEquals(listOf(lessonId.toString()), document.lessonIds)
        val teacherView = client.document(token, result.documentId).body<DocumentResponse>()
        assertEquals(listOf(STUDENT_ID), teacherView.studentIds)
        assertEquals(listOf(topicId), teacherView.topicIds)
        assertNotNull(client.awaitJob(token, job.id).publishedAt)

        // Publishing again creates nothing twice.
        val again = client.publish(token, lessonId, request).body<PublishResponse>()
        assertEquals(0, again.wordsCreated)
        assertEquals(5, again.wordsReused)
        assertEquals(0, again.wordsAssigned)
        assertEquals(0, again.topicsCreated)
        assertEquals(0, again.grammarTopicsCreated)
        assertEquals(published.topics, again.topics.map { it.copy(reused = false) })
        val rowsAgain = client.document(token, result.documentId).body<DocumentResponse>()
            .blocks.map { it.jsonObject }.single { it["type"]!!.jsonPrimitive.content == "vocab_table" }["rows"]!!.jsonArray
        assertEquals(5, rowsAgain.size)
        assertEquals(5, client.get("/api/v1/vocabulary?lessonId=$lessonId") { bearerAuth(studentToken) }.body<StudentVocabPageResponse>().words.size)
        assertEquals(5, client.get("/api/v1/vocab-entries") { bearerAuth(token) }.body<JsonObject>()["total"]!!.jsonPrimitive.content.toInt())

        // A new generation now recognizes the library words.
        val next = client.generateAndWait(token, seedLesson())
        val matched = next.nachbereitung().vocab.single { it.entry.lemma == "Gießkanne" }
        assertNotNull(matched.matchedEntryId)
        assertTrue(matched.alreadyAssigned)
        assertFalse(matched.selected)
        assertEquals(topicId, next.nachbereitung().topics.single().existingId)
    }

    @Test
    fun `publish with a matched library entry and share false keeps the document in the library`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val existing = client.post("/api/v1/vocab-entries") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody("""{ "lemma": "Teekanne", "article": "DIE", "wordType": "NOUN", "translations": { "ru": "чайник" } }""")
        }.body<JsonObject>()["id"]!!.jsonPrimitive.content
        val lessonId = seedLesson()
        val job = client.generateAndWait(token, lessonId)
        val result = job.nachbereitung()
        val teekanne = result.vocab.single { it.entry.lemma == "Teekanne" }
        assertEquals(existing, teekanne.matchedEntryId)
        assertEquals("чайник", teekanne.matchedEntry?.translations?.get("ru"))
        assertFalse(teekanne.alreadyAssigned)

        val response = client.publish(token, lessonId, PublishRequest(
            jobId = job.id,
            vocab = listOf(PublishVocabItem(teekanne.key, matchedEntryId = existing), PublishVocabItem("v1", entry = result.vocab.first().entry)),
            share = false,
        )).body<PublishResponse>()
        assertEquals(1, response.wordsCreated)
        assertEquals(1, response.wordsReused)
        assertEquals(0, response.wordsAssigned)
        assertFalse(response.shared)
        val document = client.document(token, result.documentId).body<DocumentResponse>()
        assertTrue(document.lessonIds.isEmpty())
        assertTrue(document.studentIds.isEmpty())
        assertTrue(existing in document.vocab.map { it.id })
        assertEquals(HttpStatusCode.NotFound, client.document(getStudentToken(client), result.documentId).status)
    }

    @Test
    fun `library-only publish shares nothing, a later shared publish assigns and shares without duplicates`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val studentToken = getStudentToken(client)
        val lessonId = seedLesson()
        val job = client.generateAndWait(token, lessonId)
        val result = job.nachbereitung()
        val request = publishAll(job)

        val saved = client.publish(token, lessonId, request.copy(share = false))
        assertEquals(HttpStatusCode.OK, saved.status)
        val library = saved.body<PublishResponse>()
        assertFalse(library.shared)
        assertEquals(result.vocab.size, library.wordsCreated)
        assertEquals(0, library.wordsAssigned)
        assertEquals(0, library.recipients)
        assertTrue(library.recipientIds.isEmpty())

        // Library has the words, the student has nothing, the document is neither linked nor shared.
        assertEquals(result.vocab.size, client.get("/api/v1/vocab-entries") { bearerAuth(token) }.body<JsonObject>()["total"]!!.jsonPrimitive.content.toInt())
        assertTrue(client.get("/api/v1/vocabulary?lessonId=$lessonId") { bearerAuth(studentToken) }.body<StudentVocabPageResponse>().words.isEmpty())
        val draft = client.document(token, result.documentId).body<DocumentResponse>()
        assertTrue(draft.lessonIds.isEmpty())
        assertTrue(draft.studentIds.isEmpty())
        assertEquals(result.vocab.size, draft.vocab.size)
        assertEquals(HttpStatusCode.NotFound, client.document(studentToken, result.documentId).status)
        // Lesson content is updated, but the words are not on the lesson.
        val lesson = client.get("/api/v1/lessons/$lessonId") { bearerAuth(token) }.body<LessonResponse>()
        assertEquals(ANNA_NOTES, lesson.rawNotes)
        assertEquals(listOf("Alltag"), lesson.topics.map { it.name })
        assertTrue(lesson.vocab.isEmpty())
        // Only a shared publish counts as published.
        val savedJob = client.awaitJob(token, job.id)
        assertNull(savedJob.publishedAt)
        assertNotNull(savedJob.nachbereitung().savedToLibraryAt)

        // Sharing later assigns and shares, reusing everything the library-only save created.
        val shared = client.publish(token, lessonId, request).body<PublishResponse>()
        assertTrue(shared.shared)
        assertEquals(0, shared.wordsCreated)
        assertEquals(result.vocab.size, shared.wordsReused)
        assertEquals(result.vocab.size, shared.wordsAssigned)
        assertEquals(0, shared.topicsCreated)
        assertEquals(0, shared.grammarTopicsCreated)
        assertEquals(listOf(STUDENT_ID), shared.recipientIds)
        assertEquals(result.vocab.size, client.get("/api/v1/vocab-entries") { bearerAuth(token) }.body<JsonObject>()["total"]!!.jsonPrimitive.content.toInt())
        assertEquals(result.vocab.size, client.get("/api/v1/vocabulary?lessonId=$lessonId") { bearerAuth(studentToken) }.body<StudentVocabPageResponse>().words.size)
        val document = client.document(studentToken, result.documentId).body<DocumentResponse>()
        assertEquals(listOf(lessonId.toString()), document.lessonIds)
        assertEquals(result.vocab.size, client.get("/api/v1/lessons/$lessonId") { bearerAuth(token) }.body<LessonResponse>().vocab.size)
        assertNotNull(client.awaitJob(token, job.id).publishedAt)
    }

    @Test
    fun `publish validates the job and the body`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val lessonId = seedLesson()
        val job = client.generateAndWait(token, lessonId)

        val otherLesson = client.publish(token, seedLesson(), PublishRequest(jobId = job.id))
        assertEquals("AI_JOB_LESSON_MISMATCH", otherLesson.body<ProblemDetail>().code)

        val unknownKey = client.publish(token, lessonId, PublishRequest(jobId = job.id,
            vocab = listOf(PublishVocabItem("zzz", entry = job.nachbereitung().vocab.first().entry))))
        assertEquals(HttpStatusCode.BadRequest, unknownKey.status)

        val raw = client.post("/api/v1/lessons/$lessonId/nachbereitung/publish") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody("""{ "jobId": "${job.id}", "vocab": [{ "key": "v1" }] }""")
        }
        assertEquals(HttpStatusCode.BadRequest, raw.status)
        assertEquals("VALIDATION_FAILED", raw.body<ProblemDetail>().code)

        val foreignEntry = client.post("/api/v1/lessons/$lessonId/nachbereitung/publish") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody("""{ "jobId": "${job.id}", "vocab": [{ "key": "v1", "matchedEntryId": "${UUID.randomUUID()}" }] }""")
        }
        assertEquals("VOCAB_ENTRY_NOT_FOUND", foreignEntry.body<ProblemDetail>().code)

        val rawOk = client.post("/api/v1/lessons/$lessonId/nachbereitung/publish") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody("""{ "jobId": "${job.id}", "vocab": [{ "key": "v2", "entry": { "lemma": "Gießkanne", "article": "DIE", "wordType": "NOUN",
                "translations": { "ru": "лейка" } } }], "topics": [], "grammarTopics": [] }""")
        }
        assertEquals(HttpStatusCode.OK, rawOk.status)
        assertEquals(1, rawOk.body<PublishResponse>().wordsCreated)

        val failed = client.generateAndWait(token, lessonId, "[fake:invalid]")
        val notReady = client.publish(token, lessonId, PublishRequest(jobId = failed.id))
        assertEquals(HttpStatusCode.Conflict, notReady.status)
        assertEquals("AI_JOB_NOT_READY", notReady.body<ProblemDetail>().code)
    }

    @Test
    fun `club publish gives words to every confirmed attendee and shares one overview`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val group = client.post("/api/v1/groups") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody(GroupRequest(name = "Speech Club B1", level = LanguageLevel.B1, type = GroupType.SPEECH, studentIds = listOf(STUDENT_ID)))
        }.body<GroupResponse>()
        // STUDENT_2 self-joined the session without being in the group or linked to the teacher.
        val lessonId = seedLesson(type = LessonType.SPEAKING_CLUB, students = listOf(STUDENT, STUDENT_2), groupId = UUID.fromString(group.id))

        val state = client.get("/api/v1/lessons/$lessonId/nachbereitung") { bearerAuth(token) }.body<NachbereitungState>()
        assertEquals(NachbereitungMode.CLUB, state.mode)
        assertEquals(2, state.context.attendeeCount)
        assertEquals(LanguageLevel.B1, state.context.level)
        assertEquals(listOf("de_explanation"), state.context.display.fields)

        FakeLlmGateway.received.clear()
        val job = client.generateAndWait(token, lessonId)
        assertEquals(GenerationJobStatus.SUCCEEDED, job.status)
        assertTrue("# Club session" in FakeLlmGateway.received.single().system, "club variant of the system prompt")
        val draft = client.document(token, job.documentId!!).body<DocumentResponse>()
        assertEquals("GROUP", draft.audience.name)
        val types = draft.blocks.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertTrue("grammar_box" in types && "free_sentences" in types)

        val published = client.publish(token, lessonId, publishAll(job)).body<PublishResponse>()
        assertEquals(2, published.recipients)
        assertEquals(published.wordsCreated * 2, published.wordsAssigned)

        for (studentToken in listOf(getStudentToken(client), getStudent2Token(client))) {
            val words = client.get("/api/v1/vocabulary?lessonId=$lessonId") { bearerAuth(studentToken) }.body<StudentVocabPageResponse>()
            assertEquals(published.wordsCreated, words.words.size)
            assertEquals(HttpStatusCode.OK, client.document(studentToken, job.documentId).status)
        }
        assertEquals(listOf(group.id), client.document(token, job.documentId).body<DocumentResponse>().groupIds)
    }

    private suspend fun HttpClient.patch(path: String, token: String, body: JsonObject): HttpResponse =
        patch(path) {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody(body)
        }
}
