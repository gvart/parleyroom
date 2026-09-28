package com.gvart.parleyroom.practice

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.activity.data.ActivityKind
import com.gvart.parleyroom.activity.data.LearningActivityTable
import com.gvart.parleyroom.ai.STUDENT
import com.gvart.parleyroom.ai.llm.FakeLlmGateway
import com.gvart.parleyroom.ai.service.Prompts
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.library.LibraryFixtures
import com.gvart.parleyroom.practice.data.StudentVocabSentenceTable
import com.gvart.parleyroom.practice.transfer.CreateSentenceRequest
import com.gvart.parleyroom.practice.transfer.PracticeStatsResponse
import com.gvart.parleyroom.practice.transfer.SentencePageResponse
import com.gvart.parleyroom.practice.transfer.SentenceResponse
import com.gvart.parleyroom.vocabulary.data.NounArticle
import com.gvart.parleyroom.vocabulary.data.WordType
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
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SentenceIntegrationTest : IntegrationTest() {

    private suspend fun HttpClient.write(token: String, id: UUID, sentence: String): HttpResponse =
        post("/api/v1/vocabulary/$id/sentences") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(CreateSentenceRequest(sentence))
        }

    private fun storedCount(): Long = transaction { StudentVocabSentenceTable.selectAll().count() }

    private fun sentenceCalls() = FakeLlmGateway.received.filter {
        Prompts.section(it.messages.first().text, "task") == Prompts.TASK_SENTENCE_FEEDBACK
    }

    @Test
    fun `a sentence gets feedback, is stored and counts for the streak`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)
        val id = PracticeFixtures.word("kümmern", wordType = WordType.VERB, government = "sich kümmern um + Akk.")

        val response = client.write(token, id, "  Ich kümmere mich um die Blumen.  ")
        assertEquals(HttpStatusCode.Created, response.status)
        val created = response.body<SentenceResponse>()
        assertEquals("Ich kümmere mich um die Blumen.", created.sentence)
        assertEquals("kümmern", created.word.lemma)
        assertEquals(WordType.VERB, created.word.wordType)
        assertTrue(created.feedback.isCorrect)
        assertTrue(created.feedback.usesWord)
        assertEquals("Ich kümmere mich um die Blumen.", created.feedback.corrected)
        assertEquals("Richtig, gut gemacht!", created.feedback.explanation)
        assertEquals(id.toString(), created.studentVocabId)

        val activity = transaction { LearningActivityTable.selectAll().where { LearningActivityTable.userId eq STUDENT }.single() }
        assertEquals(ActivityKind.VOCAB_SENTENCE, activity[LearningActivityTable.kind])

        val stats = client.get("/api/v1/practice/stats") { bearerAuth(token) }.body<PracticeStatsResponse>()
        assertEquals(1, stats.sentencesToday)
        assertTrue(stats.streak.todayDone)
    }

    @Test
    fun `a wrong sentence is corrected`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)
        val id = PracticeFixtures.word("Haus", article = NounArticle.DAS)

        val wrong = client.write(token, id, "das haus ist groß [fake:wrong]").body<SentenceResponse>()
        assertFalse(wrong.feedback.isCorrect)
        assertTrue(wrong.feedback.usesWord)
        assertEquals("Das haus ist groß.", wrong.feedback.corrected)

        val without = client.write(token, id, "Ich gehe jetzt.").body<SentenceResponse>()
        assertFalse(without.feedback.usesWord)
        assertFalse(without.feedback.isCorrect)
    }

    @Test
    fun `only the sentence, the word data and the level reach the AI`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)
        PracticeFixtures.setLevel(LanguageLevel.B1)
        val topic = LibraryFixtures.topic("Alltag")
        val id = PracticeFixtures.word("Kanne", article = NounArticle.DIE, government = "mit + Dat.", topicIds = listOf(topic))
        FakeLlmGateway.received.clear()

        client.write(token, id, "Die Kanne ist voll.")

        val call = sentenceCalls().single()
        val prompt = call.system + "\n" + call.messages.joinToString("\n") { it.text }
        val request = call.messages.first().text
        assertEquals("Die Kanne ist voll.", Prompts.section(request, "sentence"))
        assertEquals("B1", Prompts.section(request, "level"))
        assertEquals("lemma: Kanne\narticle: die\nwordType: NOUN\ngovernment: mit + Dat.", Prompts.section(request, "target_word"))
        assertNull(Prompts.section(request, "translation_language"))
        listOf("student@test.com", "Test", "Student", "Kanne-ru", "Kanne-en", "Erklärung", "Alltag").forEach {
            assertFalse(it in request, "prompt must not contain '$it'")
        }
        assertFalse(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").containsMatchIn(prompt), "no ids")
    }

    @Test
    fun `A1-A2 students get the explanation in their translation language`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)
        val id = PracticeFixtures.word("Haus")

        // No level: default display ["ru"]
        val beginner = client.write(token, id, "Das Haus ist alt.").body<SentenceResponse>()
        val translation = assertNotNull(beginner.feedback.explanationTranslation)
        assertEquals("ru", translation.language)
        assertEquals("(ru) Richtig, gut gemacht!", translation.text)

        PracticeFixtures.setLevel(LanguageLevel.B1)
        val advanced = client.write(token, id, "Das Haus ist alt.").body<SentenceResponse>()
        assertNull(advanced.feedback.explanationTranslation)
    }

    @Test
    fun `sentence validation`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)
        val id = PracticeFixtures.word("Haus")

        val empty = client.write(token, id, "   ")
        assertEquals(HttpStatusCode.BadRequest, empty.status)
        assertEquals("SENTENCE_EMPTY", empty.body<ProblemDetail>().code)

        val long = client.write(token, id, "Haus ".repeat(61))
        assertEquals("SENTENCE_TOO_LONG", long.body<ProblemDetail>().code)

        val malformed = client.post("/api/v1/vocabulary/$id/sentences") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody("""{"text":"Das Haus."}""")
        }
        assertEquals(HttpStatusCode.BadRequest, malformed.status)

        val raw = client.post("/api/v1/vocabulary/$id/sentences") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody("""{"sentence":"Das Haus ist rot."}""")
        }
        assertEquals(HttpStatusCode.Created, raw.status)
        assertEquals(1, storedCount())
    }

    @Test
    fun `only the student writes sentences for their own words`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val id = PracticeFixtures.word("Haus")

        assertEquals("PRACTICE_STUDENT_ONLY", client.write(teacherToken, id, "Das Haus.").body<ProblemDetail>().code)
        assertEquals("PRACTICE_STUDENT_ONLY", client.write(getStudent2Token(client), id, "Das Haus.").body<ProblemDetail>().code)
        assertEquals(0, storedCount())
    }

    @Test
    fun `daily sentence limit`() = testApp(mapOf("practice.sentences_per_day" to "2")) {
        val client = createJsonClient(this)
        val token = getStudentToken(client)
        val id = PracticeFixtures.word("Haus")

        repeat(2) { assertEquals(HttpStatusCode.Created, client.write(token, id, "Das Haus $it.").status) }
        val third = client.write(token, id, "Das Haus 3.")
        assertEquals(HttpStatusCode.TooManyRequests, third.status)
        assertEquals("AI_RATE_LIMITED", third.body<ProblemDetail>().code)
        assertEquals(2, storedCount())
    }

    @Test
    fun `AI off returns 503`() = testApp(mapOf("ai.provider" to "anthropic", "ai.anthropic_api_key" to "")) {
        val client = createJsonClient(this)
        val token = getStudentToken(client)
        val id = PracticeFixtures.word("Haus")

        val response = client.write(token, id, "Das Haus ist alt.")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("AI_NOT_CONFIGURED", response.body<ProblemDetail>().code)
    }

    @Test
    fun `provider failures are mapped and not stored`() = testApp(mapOf("practice.sentence_timeout" to "300ms")) {
        val client = createJsonClient(this)
        val token = getStudentToken(client)
        val id = PracticeFixtures.word("Haus")

        mapOf(
            "[fake:error]" to (HttpStatusCode.ServiceUnavailable to "AI_PROVIDER_ERROR"),
            "[fake:rate-limit]" to (HttpStatusCode.TooManyRequests to "AI_RATE_LIMITED"),
            "[fake:invalid]" to (HttpStatusCode.ServiceUnavailable to "AI_OUTPUT_INVALID"),
            "[fake:delay=2000]" to (HttpStatusCode.ServiceUnavailable to "AI_TIMEOUT"),
        ).forEach { (marker, expected) ->
            val response = client.write(token, id, "Das Haus $marker")
            assertEquals(expected.first, response.status, marker)
            assertEquals(expected.second, response.body<ProblemDetail>().code, marker)
        }
        assertEquals(0, storedCount())

        val retried = client.write(token, id, "Das Haus ist alt. [fake:invalid-once]")
        assertEquals(HttpStatusCode.Created, retried.status)
        assertEquals(1, storedCount())
    }

    @Test
    fun `teachers see their students' sentences per word and overall`() = testApp {
        val client = createJsonClient(this)
        val studentToken = getStudentToken(client)
        val teacherToken = getTeacherToken(client)
        val haus = PracticeFixtures.word("Haus", article = NounArticle.DAS)
        val lampe = PracticeFixtures.word("Lampe", article = NounArticle.DIE)
        client.write(studentToken, haus, "Das Haus ist alt.")
        client.write(studentToken, lampe, "Die Lampe ist hell.")
        client.write(studentToken, haus, "Mein Haus ist klein.")

        val perWord = client.get("/api/v1/vocabulary/$haus/sentences") { bearerAuth(teacherToken) }.body<List<SentenceResponse>>()
        assertEquals(listOf("Mein Haus ist klein.", "Das Haus ist alt."), perWord.map { it.sentence })

        val page = client.get("/api/v1/students/$STUDENT_ID/sentences?pageSize=2") { bearerAuth(teacherToken) }.body<SentencePageResponse>()
        assertEquals(3, page.total)
        assertEquals(2, page.sentences.size)
        assertEquals("Mein Haus ist klein.", page.sentences.first().sentence)
        assertEquals("Lampe", page.sentences[1].word.lemma)
        assertEquals(NounArticle.DIE, page.sentences[1].word.article)

        val own = client.get("/api/v1/students/$STUDENT_ID/sentences") { bearerAuth(studentToken) }
        assertEquals(HttpStatusCode.OK, own.status)

        val otherStudent = client.get("/api/v1/students/$STUDENT_ID/sentences") { bearerAuth(getStudent2Token(client)) }
        assertEquals(HttpStatusCode.Forbidden, otherStudent.status)
        val otherWord = client.get("/api/v1/vocabulary/$haus/sentences") { bearerAuth(getStudent2Token(client)) }
        assertEquals(HttpStatusCode.Forbidden, otherWord.status)

        LibraryFixtures.otherTeacher()
        val unrelated = client.get("/api/v1/students/$STUDENT_ID/sentences") { bearerAuth(getToken(client, "teacher2@test.com")) }
        assertEquals(HttpStatusCode.Forbidden, unrelated.status)
    }
}
