package com.gvart.parleyroom.ai

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.ai.data.DraftItemKind
import com.gvart.parleyroom.ai.data.GenerationJobStatus
import com.gvart.parleyroom.ai.llm.FakeLlmGateway
import com.gvart.parleyroom.ai.service.MaterialSources
import com.gvart.parleyroom.ai.service.Prompts
import com.gvart.parleyroom.ai.transfer.DraftBundleResponse
import com.gvart.parleyroom.ai.transfer.DraftContextResponse
import com.gvart.parleyroom.ai.transfer.DraftKind
import com.gvart.parleyroom.ai.transfer.DraftMaterialSource
import com.gvart.parleyroom.ai.transfer.GenerateDraftRequest
import com.gvart.parleyroom.ai.transfer.RefineDraftRequest
import com.gvart.parleyroom.ai.transfer.SendDraftRequest
import com.gvart.parleyroom.ai.transfer.SendDraftResponse
import com.gvart.parleyroom.ai.transfer.TextSourceKind
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.material.data.MaterialTable
import com.gvart.parleyroom.material.data.MaterialType
import com.gvart.parleyroom.material.transfer.MaterialResponse
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.vocabulary.data.NounArticle
import com.gvart.parleyroom.vocabulary.data.VocabEntryTable
import com.gvart.parleyroom.vocabulary.data.WordType
import com.gvart.parleyroom.vocabulary.seedStudentVocab
import com.gvart.parleyroom.vocabulary.transfer.StudentVocabPageResponse
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
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MaterialWordsIntegrationTest : IntegrationTest() {

    private val docxType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    private suspend fun HttpClient.material(token: String, name: String, fileName: String, contentType: String, bytes: ByteArray): String {
        val response = upload(token, name, fileName, contentType, bytes)
        assertEquals(HttpStatusCode.Created, response.status, response.body<String>())
        return response.body<MaterialResponse>().id
    }

    private suspend fun HttpClient.generateWords(token: String, vararg materialIds: String, prompt: String = ""): HttpResponse =
        post("/api/v1/students/$STUDENT/draft-bundles") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody(GenerateDraftRequest(prompt = prompt, kinds = listOf(DraftKind.WORDS), materialIds = materialIds.toList()))
        }

    private suspend fun HttpClient.wordsFrom(token: String, vararg materialIds: String): DraftBundleResponse {
        val response = generateWords(token, *materialIds)
        assertEquals(HttpStatusCode.Accepted, response.status, response.body<String>())
        val bundle = awaitBundle(token, response.body())
        assertEquals(GenerationJobStatus.SUCCEEDED, bundle.job?.status, bundle.job?.error?.toString())
        return bundle
    }

    private fun DraftBundleResponse.lemmas() = items.filter { it.kind == DraftItemKind.WORD }.map { it.word!!.entry.lemma }

    private fun lastPrompt() = FakeLlmGateway.received.last().messages.first().text

    private suspend fun HttpClient.studentVocab(token: String) =
        get("/api/v1/vocabulary") { bearerAuth(token) }.body<StudentVocabPageResponse>()

    @Test
    fun `words from a text material wait for Send, then Send assigns them`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val student = getStudentToken(client)
        transaction { UserTable.update({ UserTable.id eq STUDENT }) { it[level] = LanguageLevel.A2; it[nativeLanguage] = "uk" } }
        val past = seedLesson(scheduledAt = OffsetDateTime.now().minusDays(3))
        transaction { LessonTable.update({ LessonTable.id eq past }) { it[rawNotes] = "der Balkon" } }
        val materialId = client.material(token, "Garten-Text", "garten.txt", "text/plain",
            "Heute arbeiten wir im Garten. Die Gießkanne steht neben dem Schuppen.".toByteArray())

        FakeLlmGateway.received.clear()
        val bundle = client.wordsFrom(token, materialId)
        assertEquals(listOf("Garten", "Gießkanne", "Schuppen"), bundle.lemmas().filter { it.first().isUpperCase() })
        assertTrue(bundle.items.all { !it.approved })
        assertEquals(listOf(materialId), bundle.input.materialIds)
        assertEquals(listOf(DraftMaterialSource(materialId, "Garten-Text", TextSourceKind.TEXT, 69, truncated = false)), bundle.materials)
        assertTrue(bundle.items.all { "uk" in it.word!!.entry.translations }, "translations in the student's native language")

        val sent = lastPrompt()
        assertTrue("Material 1: Garten-Text\nHeute arbeiten wir im Garten." in Prompts.section(sent, "materials")!!)
        assertTrue("Level: A2" in sent)
        assertFalse("Balkon" in sent, "with materials past notes are only used when picked")

        // The open draft shows its source in the form context too.
        val context = client.get("/api/v1/students/$STUDENT/draft-context") { bearerAuth(token) }.body<DraftContextResponse>()
        assertEquals(bundle.materials, context.openBundle?.materials)

        // A refine keeps the material as its source.
        val refined = client.awaitBundle(token, client.post("/api/v1/ai/draft-bundles/${bundle.id}/refine") {
            contentType(ContentType.Application.Json); bearerAuth(token); setBody(RefineDraftRequest("Einfachere Beispiele"))
        }.body())
        assertEquals(GenerationJobStatus.SUCCEEDED, refined.job?.status)
        assertTrue("Die Gießkanne steht" in Prompts.section(lastPrompt(), "materials")!!)

        // Nothing reaches the student before Send.
        assertEquals(0L, client.studentVocab(student).total)
        client.post("/api/v1/ai/draft-bundles/${bundle.id}/approve-all") { bearerAuth(token) }
        assertEquals(0L, client.studentVocab(student).total)

        val result = client.post("/api/v1/ai/draft-bundles/${bundle.id}/send") {
            contentType(ContentType.Application.Json); bearerAuth(token); setBody(SendDraftRequest())
        }.body<SendDraftResponse>()
        assertEquals(refined.items.size, result.wordsAssigned)
        assertEquals(refined.lemmas().toSet(), client.studentVocab(student).words.map { it.lemma }.toSet())
    }

    @Test
    fun `words the student already has are left out and library words are matched`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        seedStudentVocab(STUDENT, "Gießkanne")
        val herd = transaction {
            val now = OffsetDateTime.now()
            VocabEntryTable.insertAndGetId {
                it[teacherId] = TEACHER
                it[lemma] = "Herd"
                it[article] = NounArticle.DER
                it[wordType] = WordType.NOUN
                it[translations] = mapOf("ru" to "плита")
                it[synonyms] = emptyList()
                it[createdAt] = now
                it[updatedAt] = now
            }.value
        }
        val docx = docx("Küche", "Anna stellt die Gießkanne auf den Herd.", "Dann kocht sie Suppe.")
        val materialId = client.material(token, "Küche", "kueche.docx", docxType, docx)

        FakeLlmGateway.received.clear()
        val bundle = client.wordsFrom(token, materialId)
        assertTrue("die Gießkanne" !in bundle.lemmas() && "Gießkanne" !in bundle.lemmas())
        assertTrue("gießkanne" in Prompts.section(lastPrompt(), "exclude_words")!!.lowercase(), "the model is told")
        val herdItem = bundle.items.single { it.word?.entry?.lemma == "Herd" }
        assertEquals(herd.toString(), herdItem.word!!.libraryEntryId)
        assertTrue(herdItem.word.matched)
        assertEquals(TextSourceKind.DOCX, bundle.materials.single().kind)

        // Dropped server-side even when the model ignores the list.
        val ignoring = client.material(token, "Küche 2", "k2.txt", "text/plain",
            "[fake:ignore-exclude] Die Gießkanne und der Topf stehen im Regal.".toByteArray())
        val second = client.wordsFrom(token, ignoring)
        assertEquals(listOf("Topf", "stehen", "Regal"), second.lemmas())
    }

    @Test
    fun `foreign, unknown and unreadable materials are rejected`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val foreign = transaction {
            MaterialTable.insertAndGetId {
                it[teacherId] = UUID.fromString(ADMIN_ID)
                it[name] = "Fremd"
                it[type] = MaterialType.PDF
                it[url] = "materials/fremd.txt"
                it[contentType] = "text/plain"
                it[createdAt] = OffsetDateTime.now()
            }.value.toString()
        }
        suspend fun code(response: HttpResponse) = response.status to response.body<ProblemDetail>().code

        assertEquals(HttpStatusCode.NotFound to "MATERIAL_NOT_FOUND", code(client.generateWords(token, foreign)))
        assertEquals(HttpStatusCode.NotFound to "MATERIAL_NOT_FOUND", code(client.generateWords(token, UUID.randomUUID().toString())))
        assertEquals(HttpStatusCode.BadRequest to "VALIDATION_FAILED", code(client.generateWords(token, "not-a-uuid")))

        val image = client.material(token, "Bild", "bild.png", "image/png", byteArrayOf(1, 2, 3))
        assertEquals(HttpStatusCode.BadRequest to "AI_MATERIAL_UNSUPPORTED", code(client.generateWords(token, image)))
        val empty = client.material(token, "Leer", "leer.pdf", "application/pdf", pdf(""))
        assertEquals(HttpStatusCode.BadRequest to "AI_MATERIAL_NO_TEXT", code(client.generateWords(token, empty)))

        val text = client.material(token, "Text", "t.txt", "text/plain", "Der Garten".toByteArray())
        val six = Array(6) { UUID.randomUUID().toString() }
        assertEquals(HttpStatusCode.BadRequest to "VALIDATION_FAILED", code(client.generateWords(token, *six)))
        assertEquals(HttpStatusCode.BadRequest to "VALIDATION_FAILED", code(client.generateWords(token, text, text)))
        val lesson = client.post("/api/v1/lessons/${seedLesson()}/draft-bundles") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody(GenerateDraftRequest(notes = ANNA_NOTES, materialIds = listOf(text)))
        }
        assertEquals(HttpStatusCode.BadRequest to "VALIDATION_FAILED", code(lesson))
        // Another teacher's student.
        val notMine = client.post("/api/v1/students/$STUDENT_2/draft-bundles") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody(GenerateDraftRequest(kinds = listOf(DraftKind.WORDS), materialIds = listOf(text)))
        }
        assertEquals(HttpStatusCode.Forbidden, notMine.status)
    }

    @Test
    fun `a word-list PDF alone is enough to extract words`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        // One word per line (a typical vocabulary sheet), no notes, no past lessons, no prompt.
        val list = client.material(token, "Wortliste", "wortliste.pdf", "application/pdf", pdf("Gießkanne", "Teekanne", "gießen"))
        val bundle = client.wordsFrom(token, list)
        assertEquals(listOf("Gießkanne", "Teekanne", "gießen"), bundle.lemmas())
    }

    @Test
    fun `a material with nothing new gives an empty draft, not a failed job`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        seedStudentVocab(STUDENT, "Garten")
        val known = client.material(token, "Bekannt", "bekannt.txt", "text/plain", "Im Garten.".toByteArray())
        val bundle = client.wordsFrom(token, known)
        assertEquals(emptyList(), bundle.items)
        assertEquals(listOf("Bekannt"), bundle.materials.map { it.name })
    }

    @Test
    fun `many materials stay within the word limit`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val ids = (1..5).map { m ->
            val nouns = (1..45).joinToString("\n") { "Ding${('a' + m)}${"x".repeat(it)}" }
            client.material(token, "Liste $m", "liste$m.txt", "text/plain", nouns.toByteArray())
        }
        val bundle = client.wordsFrom(token, *ids.toTypedArray())
        assertEquals(MaterialSources.MAX_WORDS, bundle.lemmas().size)
        assertTrue("at most ${MaterialSources.MAX_WORDS} in total" in Prompts.section(lastPrompt(), "material_rules")!!)
    }

    @Test
    fun `long materials are truncated and flagged`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val long = "Der Garten ist schön. ".repeat(2_500) // 55 000 characters
        val first = client.material(token, "Lang", "lang.txt", "text/plain", long.toByteArray())
        val bundle = client.wordsFrom(token, first)
        val source = bundle.materials.single()
        assertTrue(source.truncated)
        assertTrue(source.chars in 39_000..40_000, "${source.chars}")
        assertTrue("(truncated: only the beginning is included)" in lastPrompt())

        // Several long materials share the total budget.
        val second = client.material(token, "Lang 2", "lang2.txt", "text/plain", long.toByteArray())
        val shared = client.wordsFrom(token, first, second).materials
        assertEquals(listOf(true, true), shared.map { it.truncated })
        assertTrue(shared.sumOf { it.chars } <= 60_000)

        // A long PDF: 30 pages are read.
        val pages = (1..32).map { "Seite $it Wort$it" }.toTypedArray()
        val pdfId = client.material(token, "Buch", "buch.pdf", "application/pdf", pdf(*pages))
        val book = client.wordsFrom(token, pdfId).materials.single()
        assertTrue(book.truncated)
        val sent = Prompts.section(lastPrompt(), "materials")!!
        assertTrue("Wort30" in sent && "Wort31" !in sent)
    }
}
