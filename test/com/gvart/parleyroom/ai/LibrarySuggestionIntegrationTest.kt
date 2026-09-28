package com.gvart.parleyroom.ai

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.ai.transfer.LibrarySuggestions
import com.gvart.parleyroom.ai.transfer.SuggestionKind
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.document.data.DocumentAudience
import com.gvart.parleyroom.document.transfer.CreateDocumentRequest
import com.gvart.parleyroom.document.transfer.DocumentResponse
import com.gvart.parleyroom.material.data.MaterialGrammarTopicTable
import com.gvart.parleyroom.material.data.MaterialTable
import com.gvart.parleyroom.material.data.MaterialType
import com.gvart.parleyroom.topic.transfer.GrammarTopicRequest
import com.gvart.parleyroom.topic.transfer.GrammarTopicResponse
import com.gvart.parleyroom.lesson.transfer.UpdateLessonContentRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibrarySuggestionIntegrationTest : IntegrationTest() {

    private suspend fun HttpClient.document(token: String, title: String, level: LanguageLevel?, grammarIds: List<String>): String =
        post("/api/v1/documents") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody(CreateDocumentRequest(title = title, level = level, grammarTopicIds = grammarIds, audience = DocumentAudience.LIBRARY))
        }.body<DocumentResponse>().id

    @Test
    fun `lesson suggestions list matching documents and materials of the level`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val grammar = client.post("/api/v1/grammar-topics") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody(GrammarTopicRequest(name = "Präpositionen mit Dativ", level = LanguageLevel.B1))
        }.body<GrammarTopicResponse>().id
        val other = client.post("/api/v1/grammar-topics") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody(GrammarTopicRequest(name = "Konjunktiv II", level = LanguageLevel.B1))
        }.body<GrammarTopicResponse>().id

        val match1 = client.document(token, "Dativ Übungen 1", LanguageLevel.B1, listOf(grammar))
        val match2 = client.document(token, "Dativ Übungen 2", null, listOf(grammar, other))
        client.document(token, "Dativ A2", LanguageLevel.A2, listOf(grammar))
        client.document(token, "Konjunktiv", LanguageLevel.B1, listOf(other))
        val material = transaction {
            val id = MaterialTable.insertAndGetId {
                it[teacherId] = TEACHER; it[name] = "Dativ Arbeitsblatt"; it[type] = MaterialType.LINK
                it[url] = "https://example.com/dativ"; it[level] = LanguageLevel.B1; it[createdAt] = OffsetDateTime.now()
            }.value
            MaterialGrammarTopicTable.insert { it[materialId] = id; it[grammarTopicId] = UUID.fromString(grammar) }
            id
        }

        val lessonId = seedLesson(level = LanguageLevel.B1)
        val empty = client.get("/api/v1/lessons/$lessonId/library-suggestions") { bearerAuth(token) }.body<LibrarySuggestions>()
        assertTrue(empty.documents.isEmpty() && empty.summary.isEmpty())

        client.patch("/api/v1/lessons/$lessonId/content") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody(UpdateLessonContentRequest(grammarTopicIds = listOf(grammar)))
        }
        val suggestions = client.get("/api/v1/lessons/$lessonId/library-suggestions") { bearerAuth(token) }.body<LibrarySuggestions>()
        assertEquals(LanguageLevel.B1, suggestions.level)
        assertEquals(setOf(match1, match2), suggestions.documents.map { it.document.id }.toSet())
        assertTrue(suggestions.documents.all { grammar in it.matchedGrammarTopicIds })
        assertEquals(listOf(material.toString()), suggestions.materials.map { it.material.id })
        val summary = suggestions.summary.single()
        assertEquals(SuggestionKind.GRAMMAR, summary.kind)
        assertEquals("Präpositionen mit Dativ", summary.name)
        assertEquals(2, summary.documentCount)
        assertEquals(1, summary.materialCount)

        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/lessons/$lessonId/library-suggestions") { bearerAuth(getStudentToken(client)) }.status)
    }
}
