package com.gvart.parleyroom.ai

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.ai.data.DraftItemKind
import com.gvart.parleyroom.ai.llm.FakeLlmGateway
import com.gvart.parleyroom.ai.transfer.DraftBundleResponse
import com.gvart.parleyroom.ai.transfer.DraftGrammarTopic
import com.gvart.parleyroom.ai.transfer.DraftItemResponse
import com.gvart.parleyroom.ai.transfer.DraftTopic
import com.gvart.parleyroom.ai.transfer.PatchDraftItemRequest
import com.gvart.parleyroom.ai.transfer.RefineDraftRequest
import com.gvart.parleyroom.ai.transfer.SendDraftRequest
import com.gvart.parleyroom.ai.transfer.SendDraftResponse
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.library.LibraryFixtures
import com.gvart.parleyroom.topic.data.GrammarTopicTable
import com.gvart.parleyroom.topic.data.TopicTable
import com.gvart.parleyroom.vocabulary.seedStudentVocab
import com.gvart.parleyroom.vocabulary.transfer.StudentVocabPageResponse
import com.gvart.parleyroom.vocabulary.transfer.VocabEntryInput
import com.gvart.parleyroom.vocabulary.transfer.VocabEntryResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** AI-generated words: only new ones for the student, tagged with the teacher's topics and grammar. */
class AiVocabIntegrationTest : IntegrationTest() {

    private suspend fun HttpClient.generate(token: String, lessonId: UUID, prompt: String = ""): DraftBundleResponse {
        val response = startGenerate(token, lessonId, prompt)
        assertEquals(HttpStatusCode.Accepted, response.status)
        return awaitBundle(token, response.body())
    }

    private fun DraftBundleResponse.words() = items.filter { it.kind == DraftItemKind.WORD }
    private fun List<DraftItemResponse>.lemmas() = map { it.word!!.entry.lemma }

    private fun topicNamed(name: String) = transaction { TopicTable.selectAll().where { TopicTable.name eq name }.singleOrNull() }
    private fun grammarNamed(name: String) = transaction { GrammarTopicTable.selectAll().where { GrammarTopicTable.name eq name }.singleOrNull() }

    @Test
    fun `generated words the student has, spelling variants of them and repeats are dropped`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val lessonId = seedLesson()

        // Repeats within one answer: the spelled-out variants the fake adds are dropped.
        val all = listOf("Vorbeikommen", "Gießkanne", "Blumen gießen", "Teekanne", "kümmern", "Darum muss du dicht kümmern")
        assertEquals(all, client.generate(token, lessonId, "[fake:dup-words]").words().lemmas())

        // Known words, also in another spelling, case or with the article in the lemma.
        seedStudentVocab(STUDENT, lemma = "Giesskanne")
        seedStudentVocab(STUDENT, lemma = "die  TEEKANNE")
        FakeLlmGateway.received.clear()
        val words = client.generate(token, lessonId).words()
        assertEquals(listOf("Vorbeikommen", "Blumen gießen", "kümmern", "Darum muss du dicht kümmern"), words.lemmas())
        assertTrue(words.none { it.word!!.alreadyAssigned })
        // The model is told what the student has.
        val context = FakeLlmGateway.received.single().messages.first().text
        assertTrue("never put them" in context && "Giesskanne" in context && "die  TEEKANNE" in context, context)
    }

    @Test
    fun `word tags use the library by id and suggested new ones are created only on Send`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val student = getStudentToken(client)
        val alltag = LibraryFixtures.topic("Alltag")
        val perfekt = LibraryFixtures.grammar("Perfekt", LanguageLevel.B1)
        val lessonId = seedLesson(level = LanguageLevel.B1)

        FakeLlmGateway.received.clear()
        val bundle = client.generate(token, lessonId, "[fake:suggest-tags]")
        val prompt = FakeLlmGateway.received.single().messages.first().text
        assertTrue("T1: Alltag" in prompt && "G1: Perfekt (B1)" in prompt, "the library is listed with short ids")

        val expectedTopics = listOf(DraftTopic(alltag.toString(), "Alltag"), DraftTopic(null, "Garten", "Alltag"))
        val expectedGrammar = listOf(DraftGrammarTopic(perfekt.toString(), "Perfekt", LanguageLevel.B1), DraftGrammarTopic(null, "Wortbildung"))
        val word = bundle.words().first()
        assertEquals(expectedTopics, word.word!!.topics)
        assertEquals(expectedGrammar, word.word.grammarTopics)
        assertEquals(null, topicNamed("Garten"), "suggestions are not created at generation")
        assertEquals(null, grammarNamed("Wortbildung"))

        // A whole refine gives the model the ids back and keeps the tags.
        val refined = client.post("/api/v1/ai/draft-bundles/${bundle.id}/refine") {
            contentType(ContentType.Application.Json); bearerAuth(token); setBody(RefineDraftRequest("Kürzer"))
        }
        val afterRefine = client.awaitBundle(token, refined.body())
        assertTrue("{\"id\":\"T1\"}" in FakeLlmGateway.received.last().messages.first().text)
        val kept = afterRefine.words().first()
        assertEquals(expectedTopics, kept.word!!.topics)
        assertEquals(expectedGrammar, kept.word.grammarTopics)

        // The teacher removes the suggested topic before sending.
        client.patch("/api/v1/ai/draft-bundles/${bundle.id}/items/${kept.id}") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody(PatchDraftItemRequest(word = kept.word.copy(topics = kept.word.topics.filter { it.id != null }), approved = true))
        }.let { assertEquals(HttpStatusCode.OK, it.status) }

        val result = client.post("/api/v1/ai/draft-bundles/${bundle.id}/send") {
            contentType(ContentType.Application.Json); bearerAuth(token); setBody(SendDraftRequest())
        }.body<SendDraftResponse>()
        assertEquals(0, result.topicsCreated)
        assertEquals(null, topicNamed("Garten"), "a removed suggestion is never created")
        assertEquals(1, result.grammarTopicsCreated)
        val wortbildung = grammarNamed("Wortbildung")!![GrammarTopicTable.id].value

        // The word is linked to its topics and grammar topics.
        val entryId = result.words.single().entryId
        val entry = client.get("/api/v1/vocab-entries/$entryId") { bearerAuth(token) }.body<VocabEntryResponse>()
        assertEquals(listOf(alltag.toString()), entry.topicIds)
        assertEquals(setOf(perfekt, wortbildung).map(UUID::toString).toSet(), entry.grammarTopicIds.toSet())
        val studentWord = client.get("/api/v1/vocabulary") { bearerAuth(student) }.body<StudentVocabPageResponse>().words.single()
        assertEquals(entry.grammarTopicIds.toSet(), studentWord.grammarTopicIds.toSet())

        // An update that leaves grammarTopicIds out keeps them; an empty list clears them.
        suspend fun update(input: VocabEntryInput) = client.put("/api/v1/vocab-entries/$entryId") {
            contentType(ContentType.Application.Json); bearerAuth(token); setBody(input)
        }.body<VocabEntryResponse>()
        val input = VocabEntryInput(lemma = entry.lemma, article = entry.article, wordType = entry.wordType, translations = entry.translations)
        assertEquals(entry.grammarTopicIds.toSet(), update(input).grammarTopicIds.toSet())
        assertEquals(emptyList(), update(input.copy(grammarTopicIds = emptyList())).grammarTopicIds)
    }
}
