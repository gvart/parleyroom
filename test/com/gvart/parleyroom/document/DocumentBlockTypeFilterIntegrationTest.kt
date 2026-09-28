package com.gvart.parleyroom.document

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.document.transfer.DocumentPageResponse
import com.gvart.parleyroom.library.LibraryFixtures
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class DocumentBlockTypeFilterIntegrationTest : IntegrationTest() {

    private fun blocks(vararg types: String): JsonArray = Json.parseToJsonElement(
        types.joinToString(",", "[", "]") { type ->
            when (type) {
                "heading" -> """{"id":"${UUID.randomUUID()}","type":"heading","interactive":false,"text":"Titel","level":1}"""
                "gap_fill" -> """{"id":"${UUID.randomUUID()}","type":"gap_fill","interactive":true,"items":[]}"""
                else -> """{"id":"${UUID.randomUUID()}","type":"$type","interactive":false,"rows":[]}"""
            }
        },
    ) as JsonArray

    @Test
    fun `documents filter by block type and list their block types`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        LibraryFixtures.document("Nur Wörter", blocks = blocks("heading", "vocab_table"))
        LibraryFixtures.document("Übung", blocks = blocks("heading", "gap_fill", "heading", "gap_fill"))

        val gapFill = client.get("/api/v1/documents?blockType=gap_fill") { bearerAuth(token) }.body<DocumentPageResponse>()
        assertEquals(listOf("Übung"), gapFill.documents.map { it.title })
        assertEquals(listOf("heading", "gap_fill"), gapFill.documents.single().blockTypes)

        val all = client.get("/api/v1/documents?blockType=heading") { bearerAuth(token) }.body<DocumentPageResponse>()
        assertEquals(2L, all.total)
        assertEquals(listOf("heading", "vocab_table"), all.documents.single { it.title == "Nur Wörter" }.blockTypes)
    }

    @Test
    fun `an unknown block type is rejected`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val response = client.get("/api/v1/documents?blockType=%22%7D%5D") { bearerAuth(token) }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("VALIDATION_FAILED", response.body<ProblemDetail>().code)
    }
}
