package com.gvart.parleyroom.ai

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.ai.config.AiConfig
import com.gvart.parleyroom.ai.data.DraftItemKind
import com.gvart.parleyroom.ai.data.DraftMode
import com.gvart.parleyroom.ai.data.DraftScope
import com.gvart.parleyroom.ai.data.DraftStatus
import com.gvart.parleyroom.ai.data.GenerationJobKind
import com.gvart.parleyroom.ai.data.GenerationJobStatus
import com.gvart.parleyroom.ai.llm.FakeLlmGateway
import com.gvart.parleyroom.ai.service.GenerationJobRunner
import com.gvart.parleyroom.ai.transfer.DocumentDraftResponse
import com.gvart.parleyroom.ai.transfer.DraftBundleResponse
import com.gvart.parleyroom.ai.transfer.DraftBundleSummary
import com.gvart.parleyroom.ai.transfer.DraftContextResponse
import com.gvart.parleyroom.ai.transfer.DraftDocument
import com.gvart.parleyroom.ai.transfer.DraftItemResponse
import com.gvart.parleyroom.ai.transfer.DraftTopic
import com.gvart.parleyroom.ai.transfer.GenerateDraftRequest
import com.gvart.parleyroom.ai.transfer.GenerationJobResponse
import com.gvart.parleyroom.ai.transfer.PatchDraftItemRequest
import com.gvart.parleyroom.ai.transfer.RefineDraftRequest
import com.gvart.parleyroom.ai.transfer.RefineRequest
import com.gvart.parleyroom.ai.transfer.SendDraftRequest
import com.gvart.parleyroom.ai.transfer.SendDraftResponse
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.document.data.DocumentVersionReason
import com.gvart.parleyroom.document.transfer.DocumentPageResponse
import com.gvart.parleyroom.document.transfer.DocumentResponse
import com.gvart.parleyroom.document.transfer.DocumentVersionSummary
import com.gvart.parleyroom.homework.data.AssignmentItemKind
import com.gvart.parleyroom.homework.data.HomeworkResponseType
import com.gvart.parleyroom.homework.transfer.AssignmentResponse
import com.gvart.parleyroom.homework.transfer.HomeworkPageResponse
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.lesson.data.LessonTopicTable
import com.gvart.parleyroom.library.LibraryFixtures
import com.gvart.parleyroom.notification.data.NotificationType
import com.gvart.parleyroom.notification.transfer.NotificationPageResponse
import com.gvart.parleyroom.practice.transfer.PracticeQueueResponse
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.vocabulary.data.StudentVocabTable
import com.gvart.parleyroom.vocabulary.data.NounArticle
import com.gvart.parleyroom.vocabulary.data.VocabEntryTable
import com.gvart.parleyroom.vocabulary.data.WordType
import com.gvart.parleyroom.vocabulary.seedStudentVocab
import com.gvart.parleyroom.vocabulary.transfer.StudentVocabPageResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

class DraftBundleIntegrationTest : IntegrationTest() {

    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    private suspend fun HttpClient.generate(token: String, lessonId: UUID, prompt: String = "", notes: String? = ANNA_NOTES): DraftBundleResponse {
        val response = startGenerate(token, lessonId, prompt, notes)
        assertEquals(HttpStatusCode.Accepted, response.status, response.body<String>())
        return awaitBundle(token, response.body())
    }

    private suspend fun HttpClient.bundle(token: String, id: String): DraftBundleResponse =
        get("/api/v1/ai/draft-bundles/$id") { bearerAuth(token) }.body()

    private suspend fun HttpClient.patchItem(token: String, bundle: DraftBundleResponse, itemId: String, body: Any): HttpResponse =
        patch("/api/v1/ai/draft-bundles/${bundle.id}/items/$itemId") {
            contentType(ContentType.Application.Json); bearerAuth(token); setBody(body)
        }

    private suspend fun HttpClient.approve(token: String, bundle: DraftBundleResponse, item: DraftItemResponse) =
        assertEquals(HttpStatusCode.OK, patchItem(token, bundle, item.id, PatchDraftItemRequest(approved = true)).status)

    private suspend fun HttpClient.send(token: String, bundleId: String, request: SendDraftRequest = SendDraftRequest()): HttpResponse =
        post("/api/v1/ai/draft-bundles/$bundleId/send") {
            contentType(ContentType.Application.Json); bearerAuth(token); setBody(request)
        }

    private suspend fun HttpClient.refineBundle(token: String, bundleId: String, request: RefineDraftRequest): HttpResponse =
        post("/api/v1/ai/draft-bundles/$bundleId/refine") {
            contentType(ContentType.Application.Json); bearerAuth(token); setBody(request)
        }

    private data class Visible(val vocab: Long, val homework: Long, val documents: Long, val practice: Int)

    /** Everything a student can see of their teacher's work. */
    private suspend fun HttpClient.studentSees(token: String) = Visible(
        vocab = get("/api/v1/vocabulary") { bearerAuth(token) }.body<StudentVocabPageResponse>().total,
        homework = get("/api/v1/homework") { bearerAuth(token) }.body<HomeworkPageResponse>().total,
        documents = get("/api/v1/documents") { bearerAuth(token) }.body<DocumentPageResponse>().total,
        practice = get("/api/v1/practice/queue") { bearerAuth(token) }.body<PracticeQueueResponse>().cards.size,
    )

    /** A library noun the student already has. */
    private fun knownWord(lemma: String, article: NounArticle) = transaction {
        val now = OffsetDateTime.now()
        val entryId = VocabEntryTable.insertAndGetId {
            it[teacherId] = TEACHER
            it[VocabEntryTable.lemma] = lemma
            it[VocabEntryTable.article] = article
            it[wordType] = WordType.NOUN
            it[translations] = mapOf("en" to lemma)
            it[synonyms] = emptyList()
            it[createdAt] = now
            it[updatedAt] = now
        }
        StudentVocabTable.insert {
            it[studentId] = STUDENT
            it[vocabEntryId] = entryId
            it[addedAt] = now
        }
    }

    private fun DraftBundleResponse.items(kind: DraftItemKind) = items.filter { it.kind == kind }

    private fun lessonNotes(lessonId: UUID): String? =
        transaction { LessonTable.select(LessonTable.rawNotes).where { LessonTable.id eq lessonId }.single()[LessonTable.rawNotes] }

    private fun setNotes(lessonId: UUID, notes: String) = transaction {
        LessonTable.update({ LessonTable.id eq lessonId }) { it[rawNotes] = notes }
    }

    // ---- Generate ----

    @Test
    fun `generate gives words, one homework document and 1-3 tasks, all unapproved and tagged`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val alltag = LibraryFixtures.topic("Alltag")
        knownWord("Teekanne", NounArticle.DIE)
        val lessonId = seedLesson(level = LanguageLevel.B1)

        val started = client.startGenerate(token, lessonId, prompt = "Mach eine Vokabelliste.")
        assertEquals(HttpStatusCode.Accepted, started.status)
        val queued = started.body<DraftBundleResponse>()
        assertEquals(DraftStatus.DRAFT, queued.status)
        assertEquals(DraftScope.LESSON, queued.scope)
        assertEquals(GenerationJobKind.GENERATE, queued.job?.kind)
        assertEquals(queued.id, queued.job?.bundleId)

        val bundle = client.awaitBundle(token, queued)
        assertEquals(GenerationJobStatus.SUCCEEDED, bundle.job?.status)
        assertEquals(DraftMode.ONE_ON_ONE, bundle.mode)
        assertEquals(listOf(STUDENT.toString()), bundle.recipients.map { it.id })
        val words = bundle.items(DraftItemKind.WORD)
        assertEquals(listOf("Vorbeikommen", "Gießkanne", "Blumen gießen", "Teekanne", "kümmern", "Darum muss du dicht kümmern"),
            words.map { it.word!!.entry.lemma })
        assertEquals(1, bundle.items(DraftItemKind.EXERCISE_DOCUMENT).size)
        assertTrue(bundle.items(DraftItemKind.TASK).size in 1..3)
        assertTrue(bundle.items(DraftItemKind.NOTES_DOCUMENT).isEmpty())
        assertTrue(bundle.items.none { it.approved })
        assertEquals(0, bundle.approvedCount)

        val gieskanne = words.single { it.word!!.entry.lemma == "Gießkanne" }.word!!
        assertEquals("DIE", gieskanne.entry.article?.name)
        assertEquals(lessonId.toString(), gieskanne.entry.sourceLessonId)
        assertEquals(listOf(DraftTopic(alltag.toString(), "Alltag")), gieskanne.topics, "tags matched against the library")
        // The library already has Teekanne and the student knows it.
        val teekanne = words.single { it.word!!.entry.lemma == "Teekanne" }
        assertNotNull(teekanne.word!!.matchedEntryId)
        assertTrue(teekanne.word.alreadyAssigned)
        assertEquals("Teekanne", teekanne.matchedEntry?.lemma)

        val document = bundle.items(DraftItemKind.EXERCISE_DOCUMENT).single().document!!
        assertEquals(LanguageLevel.B1, document.level)
        assertEquals(listOf("Perfekt"), document.grammarTopics.map { it.name })
        assertNull(document.grammarTopics.single().id, "a new grammar tag is only created at Send")
        val task = bundle.items(DraftItemKind.TASK).first().task!!
        assertEquals(HomeworkResponseType.AUDIO, task.responseType)

        assertNull(lessonNotes(lessonId), "generate never writes the lesson's notes")
        val context = client.get("/api/v1/lessons/$lessonId/draft-context") { bearerAuth(token) }.body<DraftContextResponse>()
        assertEquals(bundle.id, context.openBundle?.id)
        assertEquals(bundle.items.size, context.openBundle?.itemCount)
    }

    @Test
    fun `generating again reuses the open draft and replaces its items`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val lessonId = seedLesson()
        val first = client.generate(token, lessonId)
        val second = client.generate(token, lessonId, notes = "die Lampe")
        assertEquals(first.id, second.id)
        assertEquals(listOf("Lampe"), second.items(DraftItemKind.WORD).map { it.word!!.entry.lemma })

        val pending = client.get("/api/v1/ai/draft-bundles?status=DRAFT") { bearerAuth(token) }.body<List<DraftBundleSummary>>()
        assertEquals(listOf(first.id), pending.map { it.id })
        assertEquals("Lektion", pending.single().lessonTitle)
    }

    @Test
    fun `the model never sees names, e-mails or ids`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        transaction {
            UserTable.update({ UserTable.id eq STUDENT }) { it[firstName] = "Olga"; it[lastName] = "Petrowa"; it[level] = LanguageLevel.A2 }
        }
        val earlier = seedLesson(scheduledAt = OffsetDateTime.now().minusDays(7))
        setNotes(earlier, "der Kühlschrank")
        val topic = LibraryFixtures.topic("Haushalt")
        val grammar = LibraryFixtures.grammar("Reflexive Verben", LanguageLevel.A2)
        LibraryFixtures.tagLesson(earlier, grammar = listOf(grammar))
        seedStudentVocab(STUDENT, lemma = "Tisch")
        val lessonId = seedLesson()

        FakeLlmGateway.received.clear()
        val bundle = client.startGenerate(token, lessonId).body<DraftBundleResponse>()
        client.awaitBundle(token, bundle)

        val call = FakeLlmGateway.received.single()
        val sent = call.system + call.messages.joinToString("\n") { it.text }
        assertTrue("Tisch" in sent, "known words are sent")
        assertTrue("Reflexive Verben (A2)" in sent, "covered grammar is sent")
        assertTrue("Haushalt" in sent, "library topics are sent")
        assertTrue("der Kühlschrank" in sent, "the latest earlier lesson's notes are sent by default")
        assertTrue("Level: A2" in sent)
        listOf("Olga", "Petrowa", "Teacher", "@test.com", "student@", STUDENT_ID, TEACHER_ID, lessonId.toString(), earlier.toString(),
            topic.toString(), grammar.toString())
            .forEach { assertFalse(it in sent, "'$it' must not be sent to the model") }
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
    fun `output invalid twice fails and leaves the draft empty without model text`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val lessonId = seedLesson()

        val bundle = client.awaitBundle(token, client.startGenerate(token, lessonId, prompt = "[fake:invalid]").body())
        val job = bundle.job!!
        assertEquals(GenerationJobStatus.FAILED, job.status)
        assertEquals("AI_OUTPUT_INVALID", job.error?.code)
        assertEquals(2, job.attempts)
        assertNull(job.result)
        assertFalse("Heute lernen" in job.error!!.message)
        assertTrue(bundle.items.isEmpty())
    }

    @Test
    fun `an empty answer is retried without replaying an empty assistant turn`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        FakeLlmGateway.received.clear()

        val job = client.generateAndWait(token, seedLesson(), prompt = "[fake:empty-once]")
        assertEquals(GenerationJobStatus.SUCCEEDED, job.status)
        assertEquals(2, job.attempts)
        assertTrue(FakeLlmGateway.received.last().messages.none { it.text.isBlank() }, "the provider rejects blank message text")
    }

    @Test
    fun `an answer cut off at the token limit fails without a retry`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        FakeLlmGateway.received.clear()

        val job = client.generateAndWait(token, seedLesson(), prompt = "[fake:truncated]")
        assertEquals(GenerationJobStatus.FAILED, job.status)
        assertEquals("AI_OUTPUT_INVALID", job.error?.code)
        assertEquals(1, job.attempts)
        assertEquals(1, FakeLlmGateway.received.size)
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
            val context = client.get("/api/v1/lessons/$lessonId/draft-context") { bearerAuth(token) }.body<DraftContextResponse>()
            assertFalse(context.aiAvailable)
            assertNull(context.openBundle, "no empty draft is left behind")
        }

    @Test
    fun `a teacher can have two active jobs, the third is rate limited, and a busy draft rejects edits`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val lessonId = seedLesson()

        val first = client.startGenerate(token, lessonId, "[fake:delay=1500]").body<DraftBundleResponse>()
        val second = client.startGenerate(token, seedLesson(), "[fake:delay=1500]").body<DraftBundleResponse>()
        val third = client.startGenerate(token, seedLesson(), "[fake:delay=1500]")
        assertEquals(HttpStatusCode.TooManyRequests, third.status)
        assertEquals("AI_RATE_LIMITED", third.body<ProblemDetail>().code)

        val again = client.startGenerate(token, lessonId)
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals("AI_DRAFT_BUSY", again.body<ProblemDetail>().code)

        assertEquals(GenerationJobStatus.SUCCEEDED, client.awaitBundle(token, first).job?.status)
        assertEquals(GenerationJobStatus.SUCCEEDED, client.awaitBundle(token, second).job?.status)
        assertEquals(HttpStatusCode.Accepted, client.startGenerate(token, seedLesson()).status)
    }

    @Test
    fun `beyond the global cap jobs wait queued`() = testApp(mapOf("ai.max_concurrent_jobs" to "1")) {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val first = client.startGenerate(token, seedLesson(), "[fake:delay=1500]").body<DraftBundleResponse>().job!!
        val second = client.startGenerate(token, seedLesson(), "[fake:delay=100]").body<DraftBundleResponse>().job!!
        delay(500)
        assertEquals(GenerationJobStatus.RUNNING, client.get("/api/v1/ai/jobs/${first.id}") { bearerAuth(token) }.body<GenerationJobResponse>().status)
        assertEquals(GenerationJobStatus.QUEUED, client.get("/api/v1/ai/jobs/${second.id}") { bearerAuth(token) }.body<GenerationJobResponse>().status)
        assertEquals(GenerationJobStatus.SUCCEEDED, client.awaitJob(token, second.id).status)
    }

    @Test
    fun `interrupted jobs are failed on startup`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val job = client.startGenerate(token, seedLesson(), "[fake:delay=3000]").body<DraftBundleResponse>().job!!

        // Simulates the next boot: a fresh runner fails what the previous process left behind.
        val runner = GenerationJobRunner(AiConfig("fake", "fake", "", 2, 3, Duration.parse("30s")))
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
        assertEquals("NACHBEREITUNG_NO_ATTENDEES", noStudent.body<ProblemDetail>().code)

        val lessonId = seedLesson()
        val nothing = client.startGenerate(token, lessonId, notes = "  ")
        assertEquals(HttpStatusCode.BadRequest, nothing.status)
        assertEquals("AI_DRAFT_NOTHING_TO_GENERATE", nothing.body<ProblemDetail>().code)

        val unknownTemplate = client.post("/api/v1/lessons/$lessonId/draft-bundles") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody(GenerateDraftRequest(notes = "Haus", promptTemplateId = UUID.randomUUID().toString()))
        }
        assertEquals("PROMPT_TEMPLATE_NOT_FOUND", unknownTemplate.body<ProblemDetail>().code)

        val otherLesson = client.post("/api/v1/lessons/$lessonId/draft-bundles") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody(GenerateDraftRequest(notes = "Haus", pastLessonIds = listOf(seedLesson(students = listOf(STUDENT_2)).toString())))
        }
        assertEquals("AI_DRAFT_PAST_LESSON_INVALID", otherLesson.body<ProblemDetail>().code)

        val tooLong = client.post("/api/v1/lessons/$lessonId/draft-bundles") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody(GenerateDraftRequest(notes = "x".repeat(GenerateDraftRequest.MAX_NOTES + 1)))
        }
        assertEquals("VALIDATION_FAILED", tooLong.body<ProblemDetail>().code)

        assertEquals(HttpStatusCode.Forbidden, client.startGenerate(getStudentToken(client), lessonId).status)
        assertNull(client.get("/api/v1/lessons/$lessonId/draft-context") { bearerAuth(token) }.body<DraftContextResponse>().openBundle)
    }

    @Test
    fun `drafts and jobs are private to their teacher`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val bundle = client.generate(token, seedLesson())
        val student = getStudentToken(client)

        val asStudent = client.get("/api/v1/ai/draft-bundles/${bundle.id}") { bearerAuth(student) }
        assertEquals(HttpStatusCode.NotFound, asStudent.status)
        assertEquals("AI_DRAFT_NOT_FOUND", asStudent.body<ProblemDetail>().code)
        assertEquals("AI_JOB_NOT_FOUND", client.get("/api/v1/ai/jobs/${bundle.job!!.id}") { bearerAuth(student) }.body<ProblemDetail>().code)
        assertEquals(HttpStatusCode.NotFound, client.send(student, bundle.id).status)
        assertEquals(HttpStatusCode.NotFound, client.patchItem(student, bundle, bundle.items.first().id, PatchDraftItemRequest(approved = true)).status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/ai/draft-bundles/${bundle.id}") { bearerAuth(getAdminToken(client)) }.status)
    }

    // ---- Review ----

    @Test
    fun `items can be edited, deleted and approved`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val haushalt = LibraryFixtures.topic("Haushalt")
        val bundle = client.generate(token, seedLesson())
        val word = bundle.items(DraftItemKind.WORD).first()
        val document = bundle.items(DraftItemKind.EXERCISE_DOCUMENT).single()
        val task = bundle.items(DraftItemKind.TASK).first()

        // Word: new lemma and tags; the library match follows the new lemma.
        seedStudentVocab(STUDENT, lemma = "Staubsauger")
        val editedWord = word.word!!.copy(
            entry = word.word.entry.copy(lemma = "Staubsauger", article = null, explanationDe = "Damit saugt man."),
            topics = listOf(DraftTopic(haushalt.toString(), "Haushalt"), DraftTopic(name = "Putzen", parentName = "Haushalt")),
        )
        val patched = client.patchItem(token, bundle, word.id, PatchDraftItemRequest(word = editedWord, approved = true)).body<DraftItemResponse>()
        assertTrue(patched.approved)
        assertEquals("Damit saugt man.", patched.word!!.entry.explanationDe)
        assertEquals(listOf("Haushalt", "Putzen"), patched.word.topics.map { it.name })
        assertNotNull(patched.word.matchedEntryId)
        assertTrue(patched.word.alreadyAssigned)

        // Document: title, blocks and tags; still validated.
        val doc = document.document!!
        val retitled = client.patchItem(token, bundle, document.id, PatchDraftItemRequest(document = doc.copy(title = "Meine Übungen")))
            .body<DraftItemResponse>()
        assertEquals("Meine Übungen", retitled.document!!.title)
        assertFalse(retitled.approved, "editing does not approve")
        val headingOnly = client.patchItem(token, bundle, document.id, PatchDraftItemRequest(document = doc.copy(blocks = JsonArray(doc.blocks.take(1)))))
        assertEquals("AI_DRAFT_DOCUMENT_INVALID", headingOnly.body<ProblemDetail>().code)
        val broken = client.patchItem(token, bundle, document.id, PatchDraftItemRequest(document = doc.copy(blocks = buildJsonArray {
            add(buildJsonObject { put("id", "x"); put("type", "nope") })
        })))
        assertEquals(HttpStatusCode.BadRequest, broken.status)
        val foreignTag = client.patchItem(token, bundle, document.id, PatchDraftItemRequest(document = doc.copy(topics = listOf(DraftTopic(UUID.randomUUID().toString(), "X")))))
        assertEquals("TOPIC_NOT_FOUND", foreignTag.body<ProblemDetail>().code)
        val mismatch = client.patchItem(token, bundle, task.id, PatchDraftItemRequest(document = doc))
        assertEquals("AI_DRAFT_ITEM_KIND_MISMATCH", mismatch.body<ProblemDetail>().code)

        // Task: instructions and response type.
        val editedTask = client.patchItem(token, bundle, task.id, PatchDraftItemRequest(task = task.task!!.copy(instructions = "Nimm ein Video auf.",
            responseType = HomeworkResponseType.VIDEO))).body<DraftItemResponse>()
        assertEquals(HomeworkResponseType.VIDEO, editedTask.task!!.responseType)

        // Unapprove, delete, approve all.
        assertFalse(client.patchItem(token, bundle, word.id, PatchDraftItemRequest(approved = false)).body<DraftItemResponse>().approved)
        val last = bundle.items(DraftItemKind.WORD).last()
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/ai/draft-bundles/${bundle.id}/items/${last.id}") { bearerAuth(token) }.status)
        val all = client.post("/api/v1/ai/draft-bundles/${bundle.id}/approve-all") { bearerAuth(token) }.body<DraftBundleResponse>()
        assertEquals(bundle.items.size - 1, all.items.size)
        assertTrue(all.items.all { it.approved })
        assertEquals(all.items.size, all.approvedCount)
        assertEquals("Meine Übungen", all.items(DraftItemKind.EXERCISE_DOCUMENT).single().document!!.title)
        val missing = client.patchItem(token, bundle, last.id, PatchDraftItemRequest(approved = true))
        assertEquals("AI_DRAFT_ITEM_NOT_FOUND", missing.body<ProblemDetail>().code)
    }

    @Test
    fun `refine replaces one item or the whole draft and needs approving again`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val bundle = client.generate(token, seedLesson())
        client.post("/api/v1/ai/draft-bundles/${bundle.id}/approve-all") { bearerAuth(token) }
        val task = bundle.items(DraftItemKind.TASK).first()
        val word = bundle.items(DraftItemKind.WORD).first()

        FakeLlmGateway.received.clear()
        val itemRefine = client.refineBundle(token, bundle.id, RefineDraftRequest("Kürzer, bitte.", itemId = task.id))
        assertEquals(HttpStatusCode.Accepted, itemRefine.status)
        val afterItem = client.awaitBundle(token, itemRefine.body())
        assertEquals(GenerationJobKind.REFINE, afterItem.job?.kind)
        val refinedTask = afterItem.items.single { it.id == task.id }
        assertTrue(refinedTask.task!!.instructions.endsWith("(überarbeitet: Kürzer, bitte.)"))
        assertFalse(refinedTask.approved, "a refined item needs approving again")
        assertTrue(afterItem.items.single { it.id == word.id }.approved, "other items keep their state")
        val sent = FakeLlmGateway.received.single().messages.single().text
        assertTrue("<target>\ntask\n</target>" in sent)
        assertFalse("Gießkanne" in sent.substringAfter("<current_output>"), "an item refine sends only that item")

        val wordRefine = client.awaitBundle(token, client.refineBundle(token, bundle.id, RefineDraftRequest("Anderes Beispiel", itemId = word.id)).body())
        assertEquals("Überarbeitet: Anderes Beispiel", wordRefine.items.single { it.id == word.id }.word!!.entry.exampleSentence)

        val whole = client.awaitBundle(token, client.refineBundle(token, bundle.id, RefineDraftRequest("Mehr Übungen")).body())
        assertEquals(bundle.items.size, whole.items.size)
        assertTrue(whole.items.none { it.approved })
        assertTrue(whole.items(DraftItemKind.EXERCISE_DOCUMENT).single().document!!.title.endsWith("(überarbeitet)"))
        assertEquals("Überarbeitet: Anderes Beispiel", whole.items(DraftItemKind.WORD).first().word!!.entry.exampleSentence,
            "a whole refine keeps earlier edits")

        val unknown = client.refineBundle(token, bundle.id, RefineDraftRequest("x", itemId = UUID.randomUUID().toString()))
        assertEquals("AI_DRAFT_ITEM_NOT_FOUND", unknown.body<ProblemDetail>().code)
    }

    // ---- Nothing before Send ----

    @Test
    fun `the student sees nothing before Send`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val student = getStudentToken(client)
        val lessonId = seedLesson()
        val before = client.studentSees(student)

        val bundle = client.generate(token, lessonId)
        client.post("/api/v1/ai/draft-bundles/${bundle.id}/approve-all") { bearerAuth(token) }
        client.patchItem(token, bundle, bundle.items(DraftItemKind.WORD).first().id, PatchDraftItemRequest(approved = false))

        assertEquals(before, client.studentSees(student))
        assertEquals("[]", client.get("/api/v1/lessons/$lessonId/documents") { bearerAuth(student) }.body<JsonArray>().toString())
        assertEquals(0L, client.get("/api/v1/notifications") { bearerAuth(student) }.body<NotificationPageResponse>().total)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/ai/draft-bundles/${bundle.id}") { bearerAuth(student) }.status)
        // Nothing reached the teacher's library either.
        assertEquals(0L, transaction { VocabEntryTable.selectAll().count() })
    }

    // ---- Send ----

    @Test
    fun `Send applies only the approved items and is idempotent`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val student = getStudentToken(client)
        val lessonId = seedLesson(level = LanguageLevel.B1)
        setNotes(lessonId, "Meine Notizen")
        val bundle = client.generate(token, lessonId)
        val words = bundle.items(DraftItemKind.WORD)
        val document = bundle.items(DraftItemKind.EXERCISE_DOCUMENT).single()
        val tasks = bundle.items(DraftItemKind.TASK)
        listOf(words[0], words[1], document, tasks[0]).forEach { client.approve(token, bundle, it) }

        val response = client.send(token, bundle.id, SendDraftRequest(title = "Hausaufgabe Haushalt"))
        assertEquals(HttpStatusCode.OK, response.status, response.body<String>())
        val result = response.body<SendDraftResponse>()
        assertEquals(listOf(STUDENT.toString()), result.recipientIds)
        assertEquals(listOf(words[0].id, words[1].id), result.words.map { it.itemId })
        assertEquals(2, result.wordsAssigned)
        assertEquals(4, result.sentItemIds.size)
        assertEquals(bundle.items.size - 4, result.skippedItemIds.size)
        assertEquals(1, result.grammarTopicsCreated, "Perfekt is created at Send")

        // Words: exactly the approved ones.
        val vocab = client.get("/api/v1/vocabulary") { bearerAuth(student) }.body<StudentVocabPageResponse>()
        assertEquals(setOf(words[0].word!!.entry.lemma, words[1].word!!.entry.lemma), vocab.words.map { it.lemma }.toSet())
        // Homework: one assignment with the document and the approved task, due in 7 days.
        val assignment = client.get("/api/v1/assignments/${result.assignmentId}") { bearerAuth(token) }.body<AssignmentResponse>()
        assertEquals("Hausaufgabe Haushalt", assignment.title)
        assertEquals(LocalDate.now().plusDays(7).toString(), assignment.dueDate)
        assertEquals(lessonId.toString(), assignment.lessonId)
        assertEquals(listOf(AssignmentItemKind.DOCUMENT, AssignmentItemKind.TASK), assignment.items.map { it.kind })
        assertEquals(tasks[0].task!!.title, assignment.items[1].title)
        assertEquals(1L, client.get("/api/v1/homework") { bearerAuth(student) }.body<HomeworkPageResponse>().total)
        // The document is shared and readable.
        val shared = client.get("/api/v1/documents/${result.exerciseDocumentId}") { bearerAuth(student) }
        assertEquals(HttpStatusCode.OK, shared.status)
        assertEquals(document.document!!.title, shared.body<DocumentResponse>().title)
        assertEquals(listOf(NotificationType.HOMEWORK_ASSIGNED),
            client.get("/api/v1/notifications") { bearerAuth(student) }.body<NotificationPageResponse>().notifications.map { it.type })
        // The lesson got the approved tags but kept its notes.
        assertEquals("Meine Notizen", lessonNotes(lessonId))
        assertTrue(transaction { LessonTopicTable.selectAll().where { LessonTopicTable.lessonId eq lessonId }.count() } > 0)

        val sentBundle = client.bundle(token, bundle.id)
        assertEquals(DraftStatus.SENT, sentBundle.status)
        assertEquals(result, sentBundle.sendResult)

        // Idempotent: the same result, nothing twice.
        val visible = client.studentSees(student)
        val again = client.send(token, bundle.id)
        assertEquals(HttpStatusCode.OK, again.status)
        assertEquals(result, again.body<SendDraftResponse>())
        assertEquals(visible, client.studentSees(student))
        assertEquals(1, client.get("/api/v1/notifications") { bearerAuth(student) }.body<NotificationPageResponse>().notifications.size)

        // A sent draft is closed; the next generate starts a new one.
        assertEquals("AI_DRAFT_NOT_EDITABLE", client.patchItem(token, bundle, words[2].id, PatchDraftItemRequest(approved = true)).body<ProblemDetail>().code)
        assertEquals("AI_DRAFT_NOT_EDITABLE", client.delete("/api/v1/ai/draft-bundles/${bundle.id}") { bearerAuth(token) }.body<ProblemDetail>().code)
        assertTrue(client.generate(token, lessonId).id != bundle.id)
    }

    @Test
    fun `Send is all or nothing`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val student = getStudentToken(client)
        val bundle = client.generate(token, seedLesson())
        val document = bundle.items(DraftItemKind.EXERCISE_DOCUMENT).single()
        // A media block with a material that is not in the library passes the draft check but fails at Send, after the words.
        val media = buildJsonObject {
            put("id", UUID.randomUUID().toString()); put("type", "media"); put("interactive", false); put("kind", "AUDIO")
            put("materialId", UUID.randomUUID().toString()); put("questions", JsonArray(emptyList()))
        }
        val withMedia = document.document!!.copy(blocks = JsonArray(document.document.blocks + media))
        assertEquals(HttpStatusCode.OK, client.patchItem(token, bundle, document.id, PatchDraftItemRequest(document = withMedia)).status)
        client.post("/api/v1/ai/draft-bundles/${bundle.id}/approve-all") { bearerAuth(token) }
        val before = client.studentSees(student)

        val failed = client.send(token, bundle.id)
        assertEquals(HttpStatusCode.BadRequest, failed.status)
        assertEquals(before, client.studentSees(student))
        assertEquals(0L, transaction { VocabEntryTable.selectAll().count() }, "the words were rolled back")
        assertEquals(DraftStatus.DRAFT, client.bundle(token, bundle.id).status)
    }

    @Test
    fun `Send validates approvals and recipients`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val bundle = client.generate(token, seedLesson())

        assertEquals("AI_DRAFT_NOTHING_APPROVED", client.send(token, bundle.id).body<ProblemDetail>().code)
        client.approve(token, bundle, bundle.items.first())
        val stranger = client.send(token, bundle.id, SendDraftRequest(studentIds = listOf(STUDENT_2.toString())))
        assertEquals("AI_DRAFT_RECIPIENT_INVALID", stranger.body<ProblemDetail>().code)
        assertEquals("AI_DRAFT_NO_RECIPIENTS", client.send(token, bundle.id, SendDraftRequest(studentIds = emptyList())).body<ProblemDetail>().code)
        assertEquals("VALIDATION_FAILED", client.send(token, bundle.id, SendDraftRequest(dueDate = "morgen")).body<ProblemDetail>().code)

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/ai/draft-bundles/${bundle.id}") { bearerAuth(token) }.status)
        assertEquals(DraftStatus.DISCARDED, client.bundle(token, bundle.id).status)
        assertEquals("AI_DRAFT_NOT_EDITABLE", client.send(token, bundle.id).body<ProblemDetail>().code)
    }

    // ---- Club ----

    @Test
    fun `a club lesson gives a notes document only, shared with the selected attendees`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val lessonId = seedLesson(type = LessonType.SPEAKING_CLUB, students = listOf(STUDENT, STUDENT_2), level = LanguageLevel.B1)

        val bundle = client.generate(token, lessonId)
        assertEquals(DraftMode.CLUB, bundle.mode)
        assertEquals(listOf(DraftItemKind.NOTES_DOCUMENT), bundle.items.map { it.kind })
        val notes = bundle.items.single().document!!
        assertTrue("# Club session" in FakeLlmGateway.received.last().system)
        assertTrue(notes.blocks.none { it.jsonObject["type"]!!.jsonPrimitive.content == "vocab_table" })

        client.approve(token, bundle, bundle.items.single())
        val result = client.send(token, bundle.id, SendDraftRequest(studentIds = listOf(STUDENT_2.toString()))).body<SendDraftResponse>()
        assertNull(result.assignmentId)
        assertTrue(result.words.isEmpty())
        val documentId = assertNotNull(result.notesDocumentId)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/documents/$documentId") { bearerAuth(getStudent2Token(client)) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/documents/$documentId") { bearerAuth(getStudentToken(client)) }.status,
            "a deselected attendee gets nothing")
        assertEquals(0L, client.get("/api/v1/homework") { bearerAuth(getStudent2Token(client)) }.body<HomeworkPageResponse>().total)
    }

    // ---- Student scope ----

    @Test
    fun `a student draft uses the selected past lesson notes`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val older = seedLesson(scheduledAt = OffsetDateTime.now().minusDays(14))
        setNotes(older, "<p>die Waschmaschine</p><p>bügeln</p>")
        val newer = seedLesson(scheduledAt = OffsetDateTime.now().minusDays(7))
        setNotes(newer, "der Balkon")
        val cancelled = seedLesson(scheduledAt = OffsetDateTime.now().minusDays(3))
        setNotes(cancelled, "abgesagt")
        val requested = seedLesson(scheduledAt = OffsetDateTime.now().minusDays(2))
        setNotes(requested, "angefragt")
        transaction {
            LessonTable.update({ LessonTable.id eq cancelled }) { it[status] = LessonStatus.CANCELLED }
            LessonTable.update({ LessonTable.id eq requested }) { it[status] = LessonStatus.REQUEST }
        }

        val context = client.get("/api/v1/students/$STUDENT/draft-context") { bearerAuth(token) }.body<DraftContextResponse>()
        assertEquals(DraftScope.STUDENT, context.scope)
        assertEquals(listOf(newer.toString(), older.toString()), context.pastLessons.map { it.id }, "cancelled and requested lessons are left out")
        assertEquals("die Waschmaschine\nbügeln", context.pastLessons.last().notesPreview)
        assertEquals(listOf(newer.toString()), context.context.pastLessons.map { it.id }, "the latest by default")

        FakeLlmGateway.received.clear()
        val started = client.post("/api/v1/students/$STUDENT/draft-bundles") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody(GenerateDraftRequest(prompt = "Neue Hausaufgabe", pastLessonIds = listOf(older.toString())))
        }
        assertEquals(HttpStatusCode.Accepted, started.status, started.body<String>())
        val bundle = client.awaitBundle(token, started.body())
        assertEquals(DraftScope.STUDENT, bundle.scope)
        assertNull(bundle.lessonId)
        assertEquals(STUDENT.toString(), bundle.studentId)
        assertEquals(listOf("Waschmaschine", "bügeln"), bundle.items(DraftItemKind.WORD).map { it.word!!.entry.lemma })
        val sent = FakeLlmGateway.received.single().messages.single().text
        assertTrue("die Waschmaschine" in sent && "Balkon" !in sent && "abgesagt" !in sent && "angefragt" !in sent)
        assertTrue("Neue Hausaufgabe" in sent)

        client.post("/api/v1/ai/draft-bundles/${bundle.id}/approve-all") { bearerAuth(token) }
        val result = client.send(token, bundle.id).body<SendDraftResponse>()
        assertEquals(2, result.wordsAssigned)
        val assignment = client.get("/api/v1/assignments/${result.assignmentId}") { bearerAuth(token) }.body<AssignmentResponse>()
        assertNull(assignment.lessonId)
        assertEquals(bundle.items(DraftItemKind.TASK).size + 1, assignment.items.size)

        val cancelledPick = client.post("/api/v1/students/$STUDENT/draft-bundles") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody(GenerateDraftRequest(pastLessonIds = listOf(cancelled.toString(), requested.toString())))
        }
        assertEquals("AI_DRAFT_PAST_LESSON_INVALID", cancelledPick.body<ProblemDetail>().code)
        val notMine = client.post("/api/v1/students/$STUDENT_2/draft-bundles") {
            contentType(ContentType.Application.Json); bearerAuth(token); setBody(GenerateDraftRequest(prompt = "x"))
        }
        assertEquals(HttpStatusCode.Forbidden, notMine.status)
    }

    // ---- Refine of a shared document ----

    @Test
    fun `refining a shared document keeps the student's content until publish`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val student = getStudentToken(client)
        val bundle = client.generate(token, seedLesson())
        val document = bundle.items(DraftItemKind.EXERCISE_DOCUMENT).single()
        client.approve(token, bundle, document)
        val documentId = client.send(token, bundle.id).body<SendDraftResponse>().exerciseDocumentId!!
        val published = client.get("/api/v1/documents/$documentId") { bearerAuth(student) }.body<DocumentResponse>()

        val started = client.post("/api/v1/documents/$documentId/ai-refine") {
            contentType(ContentType.Application.Json); bearerAuth(token); setBody(RefineRequest("Mehr Beispiele"))
        }
        assertEquals(HttpStatusCode.Accepted, started.status)
        val job = client.awaitJob(token, started.body<GenerationJobResponse>().id)
        assertEquals(GenerationJobStatus.SUCCEEDED, job.status)
        assertEquals(documentId, job.documentId)

        // The student still reads the published content; the teacher sees the draft revision.
        val stillPublished = client.get("/api/v1/documents/$documentId") { bearerAuth(student) }.body<DocumentResponse>()
        assertEquals(published.title, stillPublished.title)
        assertEquals(published.blocks, stillPublished.blocks)
        assertEquals(published.revision, client.get("/api/v1/documents/$documentId") { bearerAuth(token) }.body<DocumentResponse>().revision)
        val draft = client.get("/api/v1/documents/$documentId/draft") { bearerAuth(token) }.body<DocumentDraftResponse>()
        assertTrue(draft.title.endsWith("(überarbeitet)"))
        assertEquals(published.blocks.size + 1, draft.blocks.size)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/documents/$documentId/draft") { bearerAuth(student) }.status)

        val publish = client.post("/api/v1/documents/$documentId/draft/publish") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, publish.status, publish.body<String>())
        val now = client.get("/api/v1/documents/$documentId") { bearerAuth(student) }.body<DocumentResponse>()
        assertEquals(draft.title, now.title)
        assertEquals(draft.blocks.size, now.blocks.size)
        val versions = client.get("/api/v1/documents/$documentId/versions") { bearerAuth(token) }.body<List<DocumentVersionSummary>>()
        assertTrue(versions.any { it.reason == DocumentVersionReason.AI_REFINE && it.title == published.title })
        assertEquals("DOCUMENT_DRAFT_NOT_FOUND", client.get("/api/v1/documents/$documentId/draft") { bearerAuth(token) }.body<ProblemDetail>().code)

        // Discard: nothing changes.
        client.awaitJob(token, client.post("/api/v1/documents/$documentId/ai-refine") {
            contentType(ContentType.Application.Json); bearerAuth(token); setBody(RefineRequest("Noch mehr"))
        }.body<GenerationJobResponse>().id)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/documents/$documentId/draft") { bearerAuth(token) }.status)
        assertEquals(now.title, client.get("/api/v1/documents/$documentId") { bearerAuth(student) }.body<DocumentResponse>().title)
    }
}
