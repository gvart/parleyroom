package com.gvart.parleyroom.topic

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.ai.seedLesson
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.common.transfer.TagUsage
import com.gvart.parleyroom.document.data.DocumentGrammarTopicTable
import com.gvart.parleyroom.library.LibraryFixtures
import com.gvart.parleyroom.topic.data.GrammarTopicTable
import com.gvart.parleyroom.topic.transfer.GrammarTopicRequest
import com.gvart.parleyroom.topic.transfer.GrammarTopicResponse
import com.gvart.parleyroom.topic.transfer.MergePreview
import com.gvart.parleyroom.topic.transfer.MergeRequest
import com.gvart.parleyroom.topic.transfer.ReorderGrammarTopicsRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
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
import kotlin.test.assertTrue

class GrammarChecklistIntegrationTest : IntegrationTest() {

    private suspend fun HttpClient.create(token: String, name: String, level: LanguageLevel?): GrammarTopicResponse =
        post("/api/v1/grammar-topics") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(GrammarTopicRequest(name = name, level = level))
        }.body()

    private suspend fun HttpClient.reorder(token: String, level: LanguageLevel?, ids: List<String>): HttpResponse =
        put("/api/v1/grammar-topics/order") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(ReorderGrammarTopicsRequest(level, ids))
        }

    private suspend fun HttpClient.list(token: String, level: LanguageLevel? = null): List<GrammarTopicResponse> =
        get("/api/v1/grammar-topics" + (level?.let { "?level=$it" } ?: "")) { bearerAuth(token) }.body()

    @Test
    fun `new grammar topics are appended to their level and the list follows position`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val perfekt = client.create(token, "Perfekt", LanguageLevel.A2)
        val dativ = client.create(token, "Dativ", LanguageLevel.A2)
        val konjunktiv = client.create(token, "Konjunktiv II", LanguageLevel.B1)
        val other = client.create(token, "Aussprache", null)

        assertEquals(listOf(0, 1), listOf(perfekt.position, dativ.position))
        assertEquals(0, konjunktiv.position)
        assertEquals(0, other.position)
        assertEquals(listOf("Perfekt", "Dativ", "Konjunktiv II", "Aussprache"), client.list(token).map { it.name })
    }

    @Test
    fun `reorder sets positions within a level`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val a = client.create(token, "Perfekt", LanguageLevel.A2)
        val b = client.create(token, "Dativ", LanguageLevel.A2)
        val c = client.create(token, "Modalverben", LanguageLevel.A2)
        client.create(token, "Konjunktiv II", LanguageLevel.B1)

        val response = client.reorder(token, LanguageLevel.A2, listOf(c.id, a.id, b.id))
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf(c.id to 0, a.id to 1, b.id to 2), response.body<List<GrammarTopicResponse>>().map { it.id to it.position })
        assertEquals(listOf("Modalverben", "Perfekt", "Dativ"), client.list(token, LanguageLevel.A2).map { it.name })
    }

    @Test
    fun `reorder must list exactly the level's topics`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val a = client.create(token, "Perfekt", LanguageLevel.A2)
        val b = client.create(token, "Dativ", LanguageLevel.A2)
        val b1 = client.create(token, "Konjunktiv II", LanguageLevel.B1)

        listOf(
            listOf(a.id),
            listOf(a.id, b.id, b1.id),
            listOf(a.id, a.id, b.id),
            listOf(a.id, "not-a-uuid"),
        ).forEach { ids ->
            val response = client.reorder(token, LanguageLevel.A2, ids)
            assertEquals(HttpStatusCode.BadRequest, response.status, "ids=$ids")
            assertEquals("GRAMMAR_ORDER_INVALID", response.body<ProblemDetail>().code)
        }
        assertEquals(HttpStatusCode.OK, client.reorder(token, null, emptyList()).status)
    }

    @Test
    fun `changing the level moves a grammar topic to the end of the new level`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        client.create(token, "Konjunktiv II", LanguageLevel.B1)
        client.create(token, "Passiv", LanguageLevel.B1)
        val perfekt = client.create(token, "Perfekt", LanguageLevel.A2)

        val moved = client.put("/api/v1/grammar-topics/${perfekt.id}") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(GrammarTopicRequest(name = "Perfekt", level = LanguageLevel.B1))
        }.body<GrammarTopicResponse>()
        assertEquals(2, moved.position)

        val renamed = client.put("/api/v1/grammar-topics/${perfekt.id}") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(GrammarTopicRequest(name = "Perfekt mit sein", level = LanguageLevel.B1))
        }.body<GrammarTopicResponse>()
        assertEquals(2, renamed.position)
    }

    @Test
    fun `deleting a used grammar topic needs force and keeps the content`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val grammar = LibraryFixtures.grammar("Perfekt", LanguageLevel.A2)
        val document = LibraryFixtures.document("Übung", grammar = listOf(grammar))
        LibraryFixtures.tagLesson(seedLesson(), grammar = listOf(grammar))

        val blocked = client.delete("/api/v1/grammar-topics/$grammar") { bearerAuth(token) }
        assertEquals(HttpStatusCode.Conflict, blocked.status)
        val problem = blocked.body<ProblemDetail>()
        assertEquals("GRAMMAR_TOPIC_HAS_CONTENT", problem.code)
        assertEquals(TagUsage(words = 0, documents = 1, materials = 0, lessons = 1), problem.usage)

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/grammar-topics/$grammar?force=true") { bearerAuth(token) }.status)
        transaction {
            assertEquals(0, DocumentGrammarTopicTable.selectAll().where { DocumentGrammarTopicTable.documentId eq document }.count())
        }
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/documents/$document") { bearerAuth(token) }.status)
    }

    @Test
    fun `grammar merge re-points tags with dedupe and fills empty fields`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val source = LibraryFixtures.grammar("Perfekt (sein)", LanguageLevel.A2, category = "Verben",
            explanation = "Bewegung", examples = listOf("Ich bin gegangen.", "Er ist geblieben."))
        val target = LibraryFixtures.grammar("Perfekt", LanguageLevel.A2, position = 3, examples = listOf("Ich bin gegangen."))
        val both = LibraryFixtures.document("Beides", grammar = listOf(source, target))
        val material = LibraryFixtures.material("Tabelle", grammar = listOf(source))

        val preview = client.post("/api/v1/grammar-topics/$source/merge?dryRun=true") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(MergeRequest(target.toString()))
        }.body<MergePreview>()
        assertEquals(MergePreview(words = 0, documents = 0, materials = 1, lessons = 0, children = 0, childClashes = 0), preview)

        val merged = client.post("/api/v1/grammar-topics/$source/merge") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(MergeRequest(target.toString()))
        }.body<GrammarTopicResponse>()
        assertEquals("Perfekt", merged.name)
        assertEquals(3, merged.position)
        assertEquals("Verben", merged.category)
        assertEquals("Bewegung", merged.explanation)
        assertEquals(listOf("Ich bin gegangen.", "Er ist geblieben."), merged.examples)

        transaction {
            assertTrue(GrammarTopicTable.selectAll().where { GrammarTopicTable.id eq source }.empty())
            assertEquals(listOf(target), DocumentGrammarTopicTable.selectAll()
                .where { DocumentGrammarTopicTable.documentId eq both }.map { it[DocumentGrammarTopicTable.grammarTopicId].value })
        }
        assertEquals(listOf(target.toString()),
            client.get("/api/v1/materials/$material") { bearerAuth(token) }.body<com.gvart.parleyroom.material.transfer.MaterialResponse>().grammarTopicIds)
    }

    @Test
    fun `grammar merge into itself is invalid`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val grammar = LibraryFixtures.grammar("Perfekt")
        val response = client.post("/api/v1/grammar-topics/$grammar/merge") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(MergeRequest(grammar.toString()))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("GRAMMAR_TOPIC_MERGE_INVALID", response.body<ProblemDetail>().code)
        val unknown = client.post("/api/v1/grammar-topics/$grammar/merge") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(MergeRequest(UUID.randomUUID().toString()))
        }
        assertEquals("GRAMMAR_TOPIC_NOT_FOUND", unknown.body<ProblemDetail>().code)
    }
}
