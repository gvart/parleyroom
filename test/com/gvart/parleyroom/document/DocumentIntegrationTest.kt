package com.gvart.parleyroom.document

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.document.data.DocumentVersionReason
import com.gvart.parleyroom.document.data.DocumentVersionTable
import com.gvart.parleyroom.document.transfer.DocumentPageResponse
import com.gvart.parleyroom.document.transfer.DocumentResponse
import com.gvart.parleyroom.document.transfer.DocumentVersionResponse
import com.gvart.parleyroom.document.transfer.DocumentVersionSummary
import com.gvart.parleyroom.vocabulary.seedStudentVocab
import com.gvart.parleyroom.vocabulary.data.StudentVocabTable
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DocumentIntegrationTest : IntegrationTest() {

    private val allBlocks get() = DocumentFixtures.allBlocks

    private suspend fun HttpClient.sendJson(method: String, path: String, token: String, json: String): HttpResponse {
        val body = TextContent(json, ContentType.Application.Json)
        return when (method) {
            "POST" -> post(path) { bearerAuth(token); setBody(body) }
            "PUT" -> put(path) { bearerAuth(token); setBody(body) }
            else -> error(method)
        }
    }

    private suspend fun createDocument(
        client: HttpClient,
        token: String,
        blocks: String = allBlocks,
        extra: String = "",
        title: String = "Haushalt",
    ): HttpResponse = client.sendJson(
        "POST", "/api/v1/documents", token,
        """{"title":"$title","level":"B1","audience":"STUDENT","blocks":$blocks$extra}""",
    )

    private suspend fun putDocument(client: HttpClient, token: String, id: String, title: String, blocks: String = allBlocks) =
        client.sendJson("PUT", "/api/v1/documents/$id", token, """{"title":"$title","audience":"STUDENT","blocks":$blocks}""")

    private suspend fun versions(client: HttpClient, token: String, id: String) =
        client.get("/api/v1/documents/$id/versions") { bearerAuth(token) }.body<List<DocumentVersionSummary>>()

    @Test
    fun `teacher creates a document with every block type from a raw JSON body`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val response = createDocument(client, token)
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val document = response.body<DocumentResponse>()
        assertEquals(Json.parseToJsonElement(allBlocks), document.blocks)
        assertEquals(TEACHER_ID, document.ownerId)

        val fetched = client.get("/api/v1/documents/${document.id}") { bearerAuth(token) }.body<DocumentResponse>()
        assertEquals(13, fetched.blocks.map { it.jsonObject["type"]!!.jsonPrimitive.content }.distinct().size)
    }

    @Test
    fun `invalid blocks are rejected with a pointer`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val unknownType = createDocument(client, token, blocks = """[{"id":"${UUID.randomUUID()}","type":"crossword"}]""")
        assertEquals(HttpStatusCode.BadRequest, unknownType.status)
        val problem = unknownType.body<ProblemDetail>()
        assertEquals("DOCUMENT_INVALID_BLOCK", problem.code)
        assertEquals("/blocks/0/type", problem.pointer)

        val missingItems = createDocument(client, token, blocks = """[{"id":"${UUID.randomUUID()}","type":"gap_fill"}]""")
        assertEquals("DOCUMENT_INVALID_BLOCK", missingItems.body<ProblemDetail>().code)

        val notAnArray = createDocument(client, token, blocks = """{"type":"heading"}""")
        assertEquals(HttpStatusCode.BadRequest, notAnArray.status)

        val malformed = client.sendJson("POST", "/api/v1/documents", token, """{"title":"x","audience":"STUDENT","blocks":[""")
        assertEquals(HttpStatusCode.BadRequest, malformed.status)

        val blankTitle = createDocument(client, token, title = " ")
        assertEquals("VALIDATION_FAILED", blankTitle.body<ProblemDetail>().code)
    }

    @Test
    fun `students cannot write documents`() = testApp {
        val client = createJsonClient(this)
        val response = createDocument(client, getStudentToken(client))
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `schema endpoint serves the block JSON Schema`() = testApp {
        val client = createJsonClient(this)
        val schema = client.get("/api/v1/documents/schema") { bearerAuth(getStudentToken(client)) }
        assertEquals(HttpStatusCode.OK, schema.status)
        val defs = Json.parseToJsonElement(schema.bodyAsText()).jsonObject["\$defs"]!!.jsonObject
        assertTrue("gap_fill" in defs && "free_form" in defs && "richText" in defs)
    }

    @Test
    fun `vocab tables reference the owner's library and render entries`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val entryId = transaction {
            val studentVocabId = seedStudentVocab(UUID.fromString(STUDENT_ID), lemma = "Gießkanne")
            StudentVocabTable.selectAll().where { StudentVocabTable.id eq studentVocabId }.single()[StudentVocabTable.vocabEntryId].value
        }
        val table = """[{"id":"${UUID.randomUUID()}","type":"vocab_table","rows":[{"id":"${UUID.randomUUID()}","vocabEntryId":"$entryId"}]}]"""
        val document = createDocument(client, token, blocks = table).body<DocumentResponse>()
        assertEquals(listOf("Gießkanne"), document.vocab.map { it.lemma })
        assertEquals(mapOf("en" to "house"), document.vocab.single().translations)

        val foreign = """[{"id":"${UUID.randomUUID()}","type":"vocab_table","rows":[{"id":"${UUID.randomUUID()}","vocabEntryId":"${UUID.randomUUID()}"}]}]"""
        val problem = createDocument(client, token, blocks = foreign).body<ProblemDetail>()
        assertEquals("DOCUMENT_INVALID_BLOCK", problem.code)
        assertEquals("/blocks/0/rows/0/vocabEntryId", problem.pointer)
    }

    @Test
    fun `autosave snapshots the previous state at most every 10 minutes`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val id = createDocument(client, token, title = "v0").body<DocumentResponse>().id
        assertEquals(0, versions(client, token, id).size)

        assertEquals(HttpStatusCode.OK, putDocument(client, token, id, "v1").status)
        putDocument(client, token, id, "v2")
        val afterTwo = versions(client, token, id)
        assertEquals(listOf("v0"), afterTwo.map { it.title })
        assertEquals(DocumentVersionReason.AUTOSAVE, afterTwo.single().reason)

        transaction {
            DocumentVersionTable.update({ DocumentVersionTable.documentId eq UUID.fromString(id) }) {
                it[createdAt] = OffsetDateTime.now().minusMinutes(11)
            }
        }
        putDocument(client, token, id, "v3")
        assertEquals(listOf("v2", "v0"), versions(client, token, id).map { it.title })
        assertEquals("v3", client.get("/api/v1/documents/$id") { bearerAuth(token) }.body<DocumentResponse>().title)
    }

    @Test
    fun `restore snapshots the current state and brings the version back`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val id = createDocument(client, token, title = "Original").body<DocumentResponse>().id
        putDocument(client, token, id, "Edited", blocks = "[]")
        val original = versions(client, token, id).single()

        val version = client.get("/api/v1/documents/$id/versions/${original.id}") { bearerAuth(token) }.body<DocumentVersionResponse>()
        assertEquals(13, version.blocks.size)

        val restored = client.post("/api/v1/documents/$id/versions/${original.id}/restore") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, restored.status)
        val document = restored.body<DocumentResponse>()
        assertEquals("Original", document.title)
        assertEquals(Json.parseToJsonElement(allBlocks), document.blocks)

        val history = versions(client, token, id)
        assertEquals(listOf(DocumentVersionReason.RESTORE, DocumentVersionReason.AUTOSAVE), history.map { it.reason })
        assertEquals("Edited", history.first().title)

        val missing = client.get("/api/v1/documents/$id/versions/${UUID.randomUUID()}") { bearerAuth(token) }
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals("DOCUMENT_VERSION_NOT_FOUND", missing.body<ProblemDetail>().code)
    }

    @Test
    fun `only the newest 30 versions are kept`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val id = createDocument(client, token).body<DocumentResponse>().id
        repeat(32) {
            client.sendJson("POST", "/api/v1/documents/$id/share", token, """{"studentIds":["$STUDENT_ID"]}""")
        }
        val history = versions(client, token, id)
        assertEquals(30, history.size)
        assertEquals((32 downTo 3).toList(), history.map { it.number })
        assertTrue(history.all { it.reason == DocumentVersionReason.SHARE })
    }

    @Test
    fun `duplicate copies content with new ids and is not shared`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val source = createDocument(client, token, extra = ""","studentIds":["$STUDENT_ID"]""").body<DocumentResponse>()

        val response = client.sendJson("POST", "/api/v1/documents/${source.id}/duplicate", token, """{"title":"Haushalt (Kopie)"}""")
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val copy = response.body<DocumentResponse>()
        assertEquals("Haushalt (Kopie)", copy.title)
        assertEquals(emptyList(), copy.studentIds)
        assertEquals(source.blocks.size, copy.blocks.size)

        val sourceIds = ids(source.blocks)
        val copyIds = ids(copy.blocks)
        assertEquals(sourceIds.size, copyIds.size)
        assertTrue(sourceIds.intersect(copyIds).isEmpty())

        // The copied multiple-choice solution points at the copied option ("bin").
        val mcItem = copy.blocks.first { it.jsonObject["type"]!!.jsonPrimitive.content == "multiple_choice" }
            .jsonObject["items"]!!.jsonArray.single().jsonObject
        val correct = mcItem["solution"]!!.jsonObject["correctOptionIds"]!!.jsonArray.single().jsonPrimitive.content
        val correctText = mcItem["options"]!!.jsonArray.map { it.jsonObject }.single { it["id"]!!.jsonPrimitive.content == correct }["text"]
        assertEquals("bin", correctText!!.jsonPrimitive.content)

        assertEquals(listOf(DocumentVersionReason.DUPLICATE), versions(client, token, source.id).map { it.reason })
        // The copy passes validation as-is.
        assertEquals(HttpStatusCode.OK, putDocument(client, token, copy.id, "Kopie", blocks = copy.blocks.toString()).status)
    }

    @Test
    fun `list filters by level, audience and title`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        createDocument(client, token, title = "Haushalt")
        client.sendJson("POST", "/api/v1/documents", token, """{"title":"Reisen","level":"A2","audience":"LIBRARY","blocks":[]}""")

        suspend fun titles(query: String) = client.get("/api/v1/documents?$query") { bearerAuth(token) }
            .body<DocumentPageResponse>().documents.map { it.title }

        assertEquals(listOf("Reisen", "Haushalt"), titles(""))
        assertEquals(listOf("Haushalt"), titles("level=B1"))
        assertEquals(listOf("Reisen"), titles("audience=LIBRARY"))
        assertEquals(listOf("Haushalt"), titles("q=haus"))
        assertEquals(emptyList(), titles("studentId=$STUDENT_ID"))
    }

    @Test
    fun `owner deletes a document`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val id = createDocument(client, token).body<DocumentResponse>().id

        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/v1/documents/$id") { bearerAuth(getStudentToken(client)) }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/documents/$id") { bearerAuth(token) }.status)
        val gone = client.get("/api/v1/documents/$id") { bearerAuth(token) }
        assertEquals("DOCUMENT_NOT_FOUND", gone.body<ProblemDetail>().code)
    }

    private fun ids(blocks: JsonArray): Set<String> {
        val result = mutableSetOf<String>()
        fun visit(element: kotlinx.serialization.json.JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) ->
                    if (key == "id") result += value.jsonPrimitive.content else visit(value)
                }
                is JsonArray -> element.forEach(::visit)
                else -> Unit
            }
        }
        visit(blocks)
        return result
    }
}
