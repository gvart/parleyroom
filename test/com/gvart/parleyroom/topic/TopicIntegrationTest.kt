package com.gvart.parleyroom.topic

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.topic.transfer.CreateTopicRequest
import com.gvart.parleyroom.topic.transfer.GrammarTopicRequest
import com.gvart.parleyroom.topic.transfer.GrammarTopicResponse
import com.gvart.parleyroom.topic.transfer.TopicResponse
import com.gvart.parleyroom.topic.transfer.UpdateTopicRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TopicIntegrationTest : IntegrationTest() {

    private suspend fun createTopic(
        client: HttpClient,
        token: String,
        name: String,
        parentId: String? = null,
        levels: List<LanguageLevel> = emptyList(),
    ): HttpResponse = client.post("/api/v1/topics") {
        contentType(ContentType.Application.Json)
        bearerAuth(token)
        setBody(CreateTopicRequest(name = name, parentId = parentId, levels = levels))
    }

    @Test
    fun `teacher builds a topic tree and lists it`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val root = createTopic(client, token, "Alltag", levels = listOf(LanguageLevel.A2, LanguageLevel.B1))
        assertEquals(HttpStatusCode.Created, root.status)
        val rootId = root.body<TopicResponse>().id
        val child = createTopic(client, token, "Haushalt", parentId = rootId).body<TopicResponse>()
        assertEquals(rootId, child.parentId)

        val list = client.get("/api/v1/topics") { bearerAuth(token) }.body<List<TopicResponse>>()
        assertEquals(listOf("Alltag", "Haushalt"), list.map { it.name })
        assertEquals(listOf(LanguageLevel.A2, LanguageLevel.B1), list.first().levels)
    }

    @Test
    fun `sibling names are unique case-insensitively`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        createTopic(client, token, "Reisen")
        val dup = createTopic(client, token, "reisen")

        assertEquals(HttpStatusCode.Conflict, dup.status)
        assertEquals("TOPIC_DUPLICATE", dup.body<ProblemDetail>().code)
    }

    @Test
    fun `deleting a topic with children is rejected`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val rootId = createTopic(client, token, "Alltag").body<TopicResponse>().id
        val childId = createTopic(client, token, "Haushalt", parentId = rootId).body<TopicResponse>().id

        val blocked = client.delete("/api/v1/topics/$rootId") { bearerAuth(token) }
        assertEquals(HttpStatusCode.Conflict, blocked.status)
        assertEquals("TOPIC_HAS_CHILDREN", blocked.body<ProblemDetail>().code)

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/topics/$childId") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/topics/$rootId") { bearerAuth(token) }.status)
    }

    @Test
    fun `moving a topic under its own descendant is rejected`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val rootId = createTopic(client, token, "Alltag").body<TopicResponse>().id
        val childId = createTopic(client, token, "Haushalt", parentId = rootId).body<TopicResponse>().id

        val response = client.patch("/api/v1/topics/$rootId") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(UpdateTopicRequest(parentId = childId))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("TOPIC_CYCLE", response.body<ProblemDetail>().code)
    }

    @Test
    fun `teacher can rename and move a topic to root`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val rootId = createTopic(client, token, "Alltag").body<TopicResponse>().id
        val childId = createTopic(client, token, "Haushalt", parentId = rootId).body<TopicResponse>().id

        val updated = client.patch("/api/v1/topics/$childId") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(UpdateTopicRequest(name = "Wohnen", moveToRoot = true))
        }.body<TopicResponse>()

        assertEquals("Wohnen", updated.name)
        assertNull(updated.parentId)
    }

    @Test
    fun `student cannot create topics but can read their teacher's topics`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)
        val student2Token = getStudent2Token(client)

        createTopic(client, teacherToken, "Alltag")

        assertEquals(HttpStatusCode.Forbidden, createTopic(client, studentToken, "Mine").status)
        val visible = client.get("/api/v1/topics") { bearerAuth(studentToken) }.body<List<TopicResponse>>()
        assertEquals(1, visible.size)
        val unrelated = client.get("/api/v1/topics") { bearerAuth(student2Token) }.body<List<TopicResponse>>()
        assertEquals(0, unrelated.size)
    }

    @Test
    fun `unknown topic returns TOPIC_NOT_FOUND`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val response = client.delete("/api/v1/topics/00000000-0000-0000-0000-00000000dead") { bearerAuth(token) }
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("TOPIC_NOT_FOUND", response.body<ProblemDetail>().code)
    }

    @Test
    fun `grammar topics CRUD with level filter`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val created = client.post("/api/v1/grammar-topics") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(
                GrammarTopicRequest(
                    name = "Perfekt mit sein",
                    level = LanguageLevel.A2,
                    category = "Verben",
                    explanation = "Bewegung und Zustandsänderung",
                    examples = listOf("Ich bin geblieben."),
                )
            )
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val id = created.body<GrammarTopicResponse>().id

        client.post("/api/v1/grammar-topics") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(GrammarTopicRequest(name = "Konjunktiv II", level = LanguageLevel.B1))
        }

        val a2 = client.get("/api/v1/grammar-topics?level=A2") { bearerAuth(token) }.body<List<GrammarTopicResponse>>()
        assertEquals(listOf("Perfekt mit sein"), a2.map { it.name })
        assertEquals(listOf("Ich bin geblieben."), a2.single().examples)

        val replaced = client.put("/api/v1/grammar-topics/$id") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(GrammarTopicRequest(name = "Perfekt mit sein", level = LanguageLevel.A2, examples = emptyList()))
        }.body<GrammarTopicResponse>()
        assertEquals(emptyList(), replaced.examples)
        assertNull(replaced.category)

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/grammar-topics/$id") { bearerAuth(token) }.status)
        val missing = client.get("/api/v1/grammar-topics/$id") { bearerAuth(token) }
        assertEquals("GRAMMAR_TOPIC_NOT_FOUND", missing.body<ProblemDetail>().code)
    }
}
