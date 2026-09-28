package com.gvart.parleyroom.ai

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.ai.data.GenerationJobKind
import com.gvart.parleyroom.ai.data.GenerationJobStatus
import com.gvart.parleyroom.ai.llm.FakeLlmGateway
import com.gvart.parleyroom.ai.service.Prompts
import com.gvart.parleyroom.ai.transfer.AiStatusResponse
import com.gvart.parleyroom.ai.transfer.GenerationJobResponse
import com.gvart.parleyroom.ai.transfer.SuggestTagsResult
import com.gvart.parleyroom.ai.transfer.TextSourceKind
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.library.LibraryFixtures
import com.gvart.parleyroom.material.data.MaterialSkill
import com.gvart.parleyroom.material.data.MaterialType
import com.gvart.parleyroom.material.transfer.CreateMaterialRequest
import com.gvart.parleyroom.material.transfer.MaterialResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.forms.InputProvider
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.core.ByteReadPacket
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SuggestTagsIntegrationTest : IntegrationTest() {

    private val json = Json { ignoreUnknownKeys = true }

    /** A PDF whose pages each carry one line of text. */
    private fun pdf(vararg pages: String): ByteArray = PDDocument().use { document ->
        pages.forEach { text ->
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                stream.newLineAtOffset(72f, 700f)
                stream.showText(text)
                stream.endText()
            }
        }
        ByteArrayOutputStream().also(document::save).toByteArray()
    }

    /** A minimal DOCX: just word/document.xml with one paragraph per line. */
    private fun docx(vararg paragraphs: String): ByteArray {
        val xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
            ${paragraphs.joinToString("") { p -> "<w:p><w:r><w:t xml:space=\"preserve\">${p.substringBefore('|')}</w:t></w:r><w:r><w:tab/><w:t>${p.substringAfter('|', "")}</w:t></w:r></w:p>" }}
            </w:body></w:document>"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml")); zip.write("<Types/>".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/document.xml")); zip.write(xml.toByteArray()); zip.closeEntry()
        }
        return out.toByteArray()
    }

    private suspend fun HttpClient.upload(
        token: String,
        name: String,
        fileName: String,
        contentType: String,
        bytes: ByteArray,
        suggestTags: Boolean = false,
    ): HttpResponse = submitFormWithBinaryData(
        url = "/api/v1/materials",
        formData = formData {
            append(
                "metadata",
                Json.encodeToString(CreateMaterialRequest.serializer(), CreateMaterialRequest(name = name, type = MaterialType.PDF, suggestTags = suggestTags)),
                Headers.build { append(HttpHeaders.ContentType, "application/json") },
            )
            append(
                "file",
                InputProvider(bytes.size.toLong()) { ByteReadPacket(bytes) },
                Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                    append(HttpHeaders.ContentType, contentType)
                },
            )
        },
    ) { bearerAuth(token) }

    /** A LINK material (no file part) with suggestions on: only its name is sent. */
    private suspend fun HttpClient.uploadLink(token: String, name: String): MaterialResponse = submitFormWithBinaryData(
        url = "/api/v1/materials",
        formData = formData {
            append(
                "metadata",
                Json.encodeToString(CreateMaterialRequest.serializer(),
                    CreateMaterialRequest(name = name, type = MaterialType.LINK, url = "https://example.com/x", suggestTags = true)),
                Headers.build { append(HttpHeaders.ContentType, "application/json") },
            )
        },
    ) { bearerAuth(token) }.body()

    private fun result(job: GenerationJobResponse): SuggestTagsResult = json.decodeFromJsonElement(job.result!!)

    private fun suggestTagCalls() = FakeLlmGateway.received.filter {
        Prompts.section(it.messages.first().text, "task") == Prompts.TASK_SUGGEST_TAGS
    }

    @Test
    fun `upload with suggestTags reads the first PDF pages and matches the library`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val alltag = LibraryFixtures.topic("Alltag")
        val haushalt = LibraryFixtures.topic("Haushalt", parentId = alltag)
        val perfekt = LibraryFixtures.grammar("Perfekt", LanguageLevel.A2)

        val response = client.upload(token, "Arbeitsblatt", "blatt.pdf", "application/pdf",
            pdf("Niveau B1: Haushalt", "Wir haben das Perfekt geübt.", "Seite drei", "Seite vier mit Garten"), suggestTags = true)
        assertEquals(HttpStatusCode.Created, response.status)
        val material = response.body<MaterialResponse>()
        val jobId = assertNotNull(material.suggestTagsJobId)

        val job = client.awaitJob(token, jobId)
        assertEquals(GenerationJobStatus.SUCCEEDED, job.status)
        assertEquals(GenerationJobKind.SUGGEST_TAGS, job.kind)
        assertEquals(material.id, job.materialId)
        val result = result(job)
        assertEquals(LanguageLevel.B1, result.level)
        assertEquals(MaterialSkill.READING, result.skill)
        assertEquals(listOf(haushalt.toString()), result.topics.map { it.existingId })
        assertEquals("Alltag", result.topics.single().parentName)
        assertEquals(listOf(perfekt.toString()), result.grammarTopics.map { it.existingId })
        assertEquals(TextSourceKind.PDF, result.source.kind)
        assertFalse(result.source.truncated)

        val sent = suggestTagCalls().last().messages.first().text
        assertTrue("Seite drei" in sent, "pages 1-3 are sent")
        assertFalse("Garten" in sent, "page 4 is not sent")

        // Suggestions only: the material is untouched.
        val after = client.get("/api/v1/materials/${material.id}") { bearerAuth(token) }.body<MaterialResponse>()
        assertEquals(emptyList(), after.topicIds)
        assertNull(after.level)
    }

    @Test
    fun `suggestions for a DOCX read word document xml and propose new names`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val material = client.upload(token, "Küche", "kueche.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            docx("A2 Wortschatz|Kochen", "Der Herd und die Pfanne"), suggestTags = true).body<MaterialResponse>()

        val job = client.awaitJob(token, assertNotNull(material.suggestTagsJobId))
        assertEquals(GenerationJobStatus.SUCCEEDED, job.status)
        val result = result(job)
        assertEquals(TextSourceKind.DOCX, result.source.kind)
        assertEquals(LanguageLevel.A2, result.level)
        assertEquals(listOf("Alltag" to null), result.topics.map { it.name to it.existingId })
        assertEquals(listOf("Perfekt" to null), result.grammarTopics.map { it.name to it.existingId })

        val sent = suggestTagCalls().last().messages.first().text
        assertTrue("A2 Wortschatz Kochen\nDer Herd und die Pfanne" in sent, "paragraphs and tabs are kept: $sent")
    }

    @Test
    fun `the model only sees the material and library names, never personal data`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        LibraryFixtures.topic("Haushalt")
        FakeLlmGateway.received.clear()
        val material = client.upload(token, "Blatt", "Maria_Hausaufgabe.pdf", "application/pdf", pdf("Text von Anna"), suggestTags = true)
            .body<MaterialResponse>()
        val job = client.awaitJob(token, assertNotNull(material.suggestTagsJobId))
        assertEquals(GenerationJobStatus.SUCCEEDED, job.status)

        val call = suggestTagCalls().single()
        val sent = call.system + call.messages.joinToString("\n") { it.text }
        assertTrue("Text von Anna" in sent)
        assertTrue("Haushalt" in sent)
        listOf(TEACHER_ID, STUDENT_ID, material.id, "Maria_Hausaufgabe", "teacher@test.com", "student@test.com", "Test Teacher", "Test Student")
            .forEach { assertFalse(it in sent, "must not send $it") }
        assertFalse(Regex("[0-9a-f]{8}-[0-9a-f]{4}-").containsMatchIn(sent), "no uuids")
    }

    @Test
    fun `links use the name only, invalid output fails the job`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val job = client.awaitJob(token, assertNotNull(client.uploadLink(token, "B2 Hörverstehen").suggestTagsJobId))
        val result = result(job)
        assertEquals(TextSourceKind.NAME_ONLY, result.source.kind)
        assertEquals(LanguageLevel.B2, result.level)
        assertNull(result.skill)

        val failed = client.awaitJob(token, assertNotNull(client.uploadLink(token, "[fake:invalid]").suggestTagsJobId))
        assertEquals(GenerationJobStatus.FAILED, failed.status)
        assertEquals("AI_OUTPUT_INVALID", failed.error?.code)
    }

    @Test
    fun `an unreadable PDF falls back to the name`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val material = client.upload(token, "C1 Zeitung", "kaputt.pdf", "application/pdf", "not a pdf".toByteArray(), suggestTags = true)
            .body<MaterialResponse>()
        val job = client.awaitJob(token, assertNotNull(material.suggestTagsJobId))
        assertEquals(GenerationJobStatus.SUCCEEDED, job.status)
        assertEquals(TextSourceKind.NAME_ONLY, result(job).source.kind)
        assertEquals(LanguageLevel.C1, result(job).level)
    }

    @Test
    fun `deleting the material deletes its suggestion jobs`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val material = client.uploadLink(token, "Blatt")
        val job = client.awaitJob(token, assertNotNull(material.suggestTagsJobId))
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/materials/${material.id}") { bearerAuth(token) }.status)
        assertEquals("AI_JOB_NOT_FOUND", client.get("/api/v1/ai/jobs/${job.id}") { bearerAuth(token) }.body<ProblemDetail>().code)
    }

    @Test
    fun `ai status reports availability`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        assertEquals(AiStatusResponse(true), client.get("/api/v1/ai/status") { bearerAuth(token) }.body<AiStatusResponse>())
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/ai/status") { bearerAuth(getStudentToken(client)) }.status)
    }

    @Test
    fun `without a provider suggestions are off but uploads still work`() =
        testApp(mapOf("ai.provider" to "anthropic", "ai.anthropic_api_key" to "")) {
            val client = createJsonClient(this)
            val token = getTeacherToken(client)
            assertEquals(AiStatusResponse(false), client.get("/api/v1/ai/status") { bearerAuth(token) }.body<AiStatusResponse>())

            val upload = client.upload(token, "Blatt", "blatt.pdf", "application/pdf", pdf("Text"), suggestTags = true)
            assertEquals(HttpStatusCode.Created, upload.status)
            val material = upload.body<MaterialResponse>()
            assertNull(material.suggestTagsJobId)
        }
}
