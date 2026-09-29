package com.gvart.parleyroom.ai

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.ai.data.DraftItemKind
import com.gvart.parleyroom.ai.llm.FakeLlmGateway
import com.gvart.parleyroom.ai.service.AiOutputInvalid
import com.gvart.parleyroom.ai.service.AiOutputParser
import com.gvart.parleyroom.ai.service.DraftTarget
import com.gvart.parleyroom.ai.service.Prompts
import com.gvart.parleyroom.ai.transfer.DraftBundleResponse
import com.gvart.parleyroom.ai.transfer.DraftContextResponse
import com.gvart.parleyroom.ai.transfer.DraftKind
import com.gvart.parleyroom.ai.transfer.GenerateDraftRequest
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.user.data.UserTable
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeLanguageAiIntegrationTest : IntegrationTest() {

    private fun setNativeLanguage(studentId: UUID, language: String) = transaction {
        UserTable.update({ UserTable.id eq studentId }) { it[nativeLanguage] = language }
    }

    private fun generateCall() = FakeLlmGateway.received.last {
        Prompts.section(it.messages.first().text, "task") == Prompts.TASK_GENERATE
    }

    @Test
    fun `a draft for a uk student demands and returns uk translations`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        setNativeLanguage(STUDENT, "uk")
        transaction { UserTable.update({ UserTable.id eq STUDENT }) { it[level] = LanguageLevel.A2 } }

        val context = client.get("/api/v1/students/$STUDENT/draft-context") { bearerAuth(token) }.body<DraftContextResponse>()
        assertEquals(listOf("uk"), context.context.translationLanguages)
        assertEquals(listOf("uk"), context.context.display.fields, "the A1–A2 default shows the native language")

        val started = client.post("/api/v1/students/$STUDENT/draft-bundles") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody(GenerateDraftRequest(notes = ANNA_NOTES, kinds = listOf(DraftKind.WORDS, DraftKind.HOMEWORK)))
        }
        assertEquals(HttpStatusCode.Accepted, started.status, started.body<String>())
        val bundle = client.awaitBundle(token, started.body<DraftBundleResponse>())

        val request = Prompts.section(generateCall().messages.first().text, "context")!!
        assertTrue("Translation languages (the learners' native languages): uk. Every word MUST have translations in all of them." in request)
        assertTrue("Homework instructions: German, each followed by a short hint in uk" in request, "A1–A2 get a native hint")
        val words = bundle.items.filter { it.kind == DraftItemKind.WORD }
        assertTrue(words.isNotEmpty())
        words.forEach { assertEquals("${it.word!!.entry.lemma} (uk)", it.word.entry.translations["uk"]) }
        val task = bundle.items.first { it.kind == DraftItemKind.TASK }.task!!
        assertTrue(task.instructions.endsWith("(uk: Hinweis)"))
    }

    @Test
    fun `B1 homework instructions stay German only`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        client.generateAndWait(token, seedLesson(level = LanguageLevel.B1))

        val context = Prompts.section(generateCall().messages.first().text, "context")!!
        assertTrue("Homework instructions: German only." in context)
        assertTrue("native languages): ru." in context)
    }

    @Test
    fun `a club with ru and uk attendees gets both languages`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        setNativeLanguage(STUDENT_2, "uk")
        val lessonId = seedLesson(type = LessonType.SPEAKING_CLUB, students = listOf(STUDENT, STUDENT_2), level = LanguageLevel.A2)

        val context = client.get("/api/v1/lessons/$lessonId/draft-context") { bearerAuth(token) }.body<DraftContextResponse>()
        assertEquals(listOf("ru", "uk"), context.context.translationLanguages)

        val bundle = client.awaitBundle(token, client.startGenerate(token, lessonId).body<DraftBundleResponse>())
        val prompt = Prompts.section(generateCall().messages.first().text, "context")!!
        assertTrue("Glosses for new words in the notes document: ru, uk." in prompt)
        val notes = bundle.items.single { it.kind == DraftItemKind.NOTES_DOCUMENT }.document!!.blocks.toString()
        assertTrue("Gießkanne (ru: Gießkanne (ru); uk: Gießkanne (uk))" in notes, notes)
    }

    @Test
    fun `words without a required translation are rejected`() {
        val answer = """{ "words": [ { "lemma": "Haus", "article": "DAS", "wordType": "NOUN", "translations": { "ru": "дом" } } ] }"""
        val issues = assertFailsWith<AiOutputInvalid> {
            AiOutputParser.parseDraft(answer, DraftTarget.BUNDLE, setOf(DraftKind.WORDS), listOf("ru", "uk"))
        }.issues
        assertEquals(listOf("/words/0/translations" to "missing translations: uk"), issues.map { it.pointer to it.message })
        AiOutputParser.parseDraft(answer, DraftTarget.BUNDLE, setOf(DraftKind.WORDS), listOf("ru"))
    }
}
