package com.gvart.parleyroom.ai

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.ai.data.GenerationJobStatus
import com.gvart.parleyroom.ai.transfer.DraftBundleResponse
import com.gvart.parleyroom.ai.transfer.GenerateDraftRequest
import com.gvart.parleyroom.ai.transfer.GenerationJobResponse
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.lesson.data.LessonStudentStatus
import com.gvart.parleyroom.lesson.data.LessonStudentTable
import com.gvart.parleyroom.lesson.data.LessonTable
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import com.gvart.parleyroom.material.data.MaterialType
import com.gvart.parleyroom.material.transfer.CreateMaterialRequest
import io.ktor.client.request.forms.InputProvider
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.core.ByteReadPacket
import kotlinx.serialization.json.Json
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.fail

val TEACHER: UUID = UUID.fromString(IntegrationTest.TEACHER_ID)
val STUDENT: UUID = UUID.fromString(IntegrationTest.STUDENT_ID)
val STUDENT_2: UUID = UUID.fromString(IntegrationTest.STUDENT_2_ID)

const val ANNA_NOTES = """Vorbeikommen
die Gießkanne
Blumen gießen
Teekanne
kümmern
Darum muss du dicht kümmern        <- student error
41 eigentlich mache alles ich - Abwasch mann"""

/** A lesson of the test teacher with the given confirmed students, seeded directly. */
fun seedLesson(
    type: LessonType = LessonType.ONE_ON_ONE,
    students: List<UUID> = listOf(STUDENT),
    groupId: UUID? = null,
    level: LanguageLevel? = null,
    scheduledAt: OffsetDateTime = OffsetDateTime.now().minusHours(2),
): UUID = transaction {
    val now = OffsetDateTime.now()
    val id = LessonTable.insertAndGetId {
        it[title] = "Lektion"
        it[LessonTable.type] = type
        it[LessonTable.scheduledAt] = scheduledAt
        it[teacherId] = TEACHER
        it[status] = LessonStatus.COMPLETED
        it[topic] = "Alltag"
        it[LessonTable.level] = level
        it[LessonTable.groupId] = groupId
        it[createdBy] = TEACHER
        it[createdAt] = now
        it[updatedAt] = now
    }.value
    students.forEach { studentId ->
        LessonStudentTable.insert {
            it[lessonId] = id
            it[LessonStudentTable.studentId] = studentId
            it[status] = LessonStudentStatus.CONFIRMED
        }
    }
    id
}

suspend fun HttpClient.startGenerate(token: String, lessonId: UUID, prompt: String = "", notes: String? = ANNA_NOTES): HttpResponse =
    post("/api/v1/lessons/$lessonId/draft-bundles") {
        contentType(ContentType.Application.Json)
        bearerAuth(token)
        setBody(GenerateDraftRequest(notes = notes, prompt = prompt))
    }

/** Polls a job until it is SUCCEEDED or FAILED. */
suspend fun HttpClient.awaitJob(token: String, jobId: String): GenerationJobResponse {
    repeat(300) {
        val job = get("/api/v1/ai/jobs/$jobId") { bearerAuth(token) }.body<GenerationJobResponse>()
        if (job.status == GenerationJobStatus.SUCCEEDED || job.status == GenerationJobStatus.FAILED) return job
        delay(50)
    }
    fail("Job $jobId did not finish")
}

/** Waits for the bundle's latest job and returns the bundle. */
suspend fun HttpClient.awaitBundle(token: String, bundle: DraftBundleResponse): DraftBundleResponse {
    awaitJob(token, bundle.job!!.id)
    return get("/api/v1/ai/draft-bundles/${bundle.id}") { bearerAuth(token) }.body()
}

/** Generates the lesson's draft and returns its finished job. */
suspend fun HttpClient.generateAndWait(token: String, lessonId: UUID, prompt: String = ""): GenerationJobResponse =
    awaitJob(token, startGenerate(token, lessonId, prompt).body<DraftBundleResponse>().job!!.id)

/** A PDF whose pages each carry one line of text. */
fun pdf(vararg pages: String): ByteArray = PDDocument().use { document ->
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
fun docx(vararg paragraphs: String): ByteArray {
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

/** Uploads a file material (type PDF: any stored file). */
suspend fun HttpClient.upload(
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
