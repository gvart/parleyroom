package com.gvart.parleyroom.ai

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.ai.data.GenerationJobStatus
import com.gvart.parleyroom.ai.transfer.GenerateRequest
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

suspend fun HttpClient.startGenerate(token: String, lessonId: UUID, prompt: String = "", notes: String = ANNA_NOTES): HttpResponse =
    post("/api/v1/lessons/$lessonId/nachbereitung/generate") {
        contentType(ContentType.Application.Json)
        bearerAuth(token)
        setBody(GenerateRequest(notes = notes, prompt = prompt))
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

suspend fun HttpClient.generateAndWait(token: String, lessonId: UUID, prompt: String = ""): GenerationJobResponse =
    awaitJob(token, startGenerate(token, lessonId, prompt).body<GenerationJobResponse>().id)
