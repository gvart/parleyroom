package com.gvart.parleyroom.material

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.material.transfer.MaterialPageResponse
import com.gvart.parleyroom.material.transfer.MaterialResponse
import com.gvart.parleyroom.material.transfer.UpdateMaterialRequest
import com.gvart.parleyroom.topic.transfer.CreateTopicRequest
import com.gvart.parleyroom.topic.transfer.GrammarTopicRequest
import com.gvart.parleyroom.topic.transfer.GrammarTopicResponse
import com.gvart.parleyroom.topic.transfer.TopicResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals

class MaterialTagIntegrationTest : IntegrationTest() {

    private suspend fun createLink(client: HttpClient, token: String, name: String): String =
        client.post("/api/v1/materials") {
            bearerAuth(token)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            "metadata",
                            """{"name":"$name","type":"LINK","url":"https://example.com/$name"}""",
                            Headers.build { append(HttpHeaders.ContentType, "application/json") },
                        )
                    }
                )
            )
        }.also { assertEquals(HttpStatusCode.Created, it.status) }.body<MaterialResponse>().id

    private suspend fun update(client: HttpClient, token: String, id: String, request: UpdateMaterialRequest): HttpResponse =
        client.put("/api/v1/materials/$id") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(request)
        }

    @Test
    fun `teacher tags a material with level, topics and grammar and filters by them`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val topicId = client.post("/api/v1/topics") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(CreateTopicRequest(name = "Reisen"))
        }.body<TopicResponse>().id
        val grammarId = client.post("/api/v1/grammar-topics") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(GrammarTopicRequest(name = "Präpositionen", level = LanguageLevel.A2))
        }.body<GrammarTopicResponse>().id
        val tagged = createLink(client, token, "bahn")
        createLink(client, token, "other")

        val response = update(
            client, token, tagged,
            UpdateMaterialRequest(level = LanguageLevel.A2, topicIds = listOf(topicId), grammarTopicIds = listOf(grammarId)),
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val material = response.body<MaterialResponse>()
        assertEquals(LanguageLevel.A2, material.level)
        assertEquals(listOf(topicId), material.topicIds)
        assertEquals(listOf(grammarId), material.grammarTopicIds)

        val byTopic = client.get("/api/v1/materials?topicId=$topicId") { bearerAuth(token) }.body<MaterialPageResponse>()
        assertEquals(listOf(tagged), byTopic.materials.map { it.id })
        val byGrammar = client.get("/api/v1/materials?grammarTopicId=$grammarId") { bearerAuth(token) }.body<MaterialPageResponse>()
        assertEquals(listOf(tagged), byGrammar.materials.map { it.id })

        val cleared = update(client, token, tagged, UpdateMaterialRequest(topicIds = emptyList())).body<MaterialResponse>()
        assertEquals(emptyList(), cleared.topicIds)
        assertEquals(listOf(grammarId), cleared.grammarTopicIds)
    }

    @Test
    fun `tagging with an unknown topic is rejected`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val id = createLink(client, token, "bahn")

        val response = update(client, token, id, UpdateMaterialRequest(topicIds = listOf("00000000-0000-0000-0000-00000000beef")))
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("TOPIC_NOT_FOUND", response.body<ProblemDetail>().code)
    }
}
