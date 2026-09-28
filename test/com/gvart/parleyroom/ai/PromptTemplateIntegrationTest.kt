package com.gvart.parleyroom.ai

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.ai.data.PromptTemplateLessonType
import com.gvart.parleyroom.ai.transfer.PromptTemplateInput
import com.gvart.parleyroom.ai.transfer.PromptTemplateResponse
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.transfer.ProblemDetail
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
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class PromptTemplateIntegrationTest : IntegrationTest() {

    private suspend fun HttpClient.create(token: String, body: Any): HttpResponse = post("/api/v1/prompt-templates") {
        contentType(ContentType.Application.Json)
        bearerAuth(token)
        setBody(body)
    }

    @Test
    fun `teacher manages prompt templates`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val created = client.create(token, """{ "name": "Wortschatz B1", "text": "Vokabelliste mit deutschen Erklärungen", "level": "B1", "lessonType": "ONE_ON_ONE" }""")
        assertEquals(HttpStatusCode.Created, created.status)
        val template = created.body<PromptTemplateResponse>()
        assertEquals(TEACHER_ID, template.teacherId)
        assertEquals(LanguageLevel.B1, template.level)
        client.create(token, PromptTemplateInput("Club", "Ein Überblick", lessonType = PromptTemplateLessonType.CLUB))
        client.create(token, PromptTemplateInput("allgemein", "Immer passend"))

        val all = client.get("/api/v1/prompt-templates") { bearerAuth(token) }.body<List<PromptTemplateResponse>>()
        assertEquals(listOf("allgemein", "Club", "Wortschatz B1"), all.map { it.name })
        val oneOnOne = client.get("/api/v1/prompt-templates?lessonType=ONE_ON_ONE&level=B1") { bearerAuth(token) }
            .body<List<PromptTemplateResponse>>()
        assertEquals(listOf("allgemein", "Wortschatz B1"), oneOnOne.map { it.name })

        val updated = client.put("/api/v1/prompt-templates/${template.id}") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody(PromptTemplateInput("Wortschatz B1+", "Neu", LanguageLevel.B2))
        }.body<PromptTemplateResponse>()
        assertEquals("Wortschatz B1+", updated.name)
        assertEquals(null, updated.lessonType)

        val duplicate = client.create(token, PromptTemplateInput("CLUB", "x"))
        assertEquals(HttpStatusCode.Conflict, duplicate.status)
        assertEquals("PROMPT_TEMPLATE_DUPLICATE", duplicate.body<ProblemDetail>().code)

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/prompt-templates/${template.id}") { bearerAuth(token) }.status)
        val gone = client.get("/api/v1/prompt-templates/${template.id}") { bearerAuth(token) }
        assertEquals(HttpStatusCode.NotFound, gone.status)
        assertEquals("PROMPT_TEMPLATE_NOT_FOUND", gone.body<ProblemDetail>().code)
    }

    @Test
    fun `templates validate their body and are teacher only`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val blank = client.create(token, """{ "name": " ", "text": "x" }""")
        assertEquals(HttpStatusCode.BadRequest, blank.status)
        assertEquals("VALIDATION_FAILED", blank.body<ProblemDetail>().code)
        val tooLong = client.create(token, PromptTemplateInput("n", "x".repeat(10_001)))
        assertEquals(HttpStatusCode.BadRequest, tooLong.status)
        val badType = client.create(token, """{ "name": "n", "text": "x", "lessonType": "GROUP" }""")
        assertEquals(HttpStatusCode.BadRequest, badType.status)

        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/prompt-templates") { bearerAuth(getStudentToken(client)) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.create(getAdminToken(client), PromptTemplateInput("n", "x")).status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/prompt-templates/${UUID.randomUUID()}") { bearerAuth(token) }.status)
    }
}
