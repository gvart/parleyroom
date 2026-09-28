package com.gvart.parleyroom.homework

import com.gvart.parleyroom.homework.transfer.AssignmentResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals

object HomeworkFixtures {

    /** POSTs a raw JSON body to /api/v1/assignments. */
    suspend fun postAssignment(client: HttpClient, token: String, body: String): HttpResponse =
        client.post("/api/v1/assignments") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    /** One TASK item (TEXT answer) for the given students. */
    suspend fun assignTextTask(client: HttpClient, token: String, vararg studentIds: String): AssignmentResponse {
        val response = postAssignment(
            client, token,
            """{ "title": "Schreib über dein Wochenende", "studentIds": [${studentIds.joinToString { "\"$it\"" }}],
                 "items": [{ "kind": "TASK", "title": "Text", "task": "5 Sätze", "responseType": "TEXT" }] }""",
        )
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }

    const val GAP_BLOCK = "20000000-0000-0000-0000-000000000001"
    const val GAP_1 = "20000000-0000-0000-0000-000000000002"
    const val GAP_2 = "20000000-0000-0000-0000-000000000003"
    const val MC_BLOCK = "20000000-0000-0000-0000-000000000004"
    const val MC_1 = "20000000-0000-0000-0000-000000000005"
    const val OPT_HABE = "20000000-0000-0000-0000-000000000006"
    const val OPT_BIN = "20000000-0000-0000-0000-000000000007"
    const val READING_BLOCK = "20000000-0000-0000-0000-000000000008"
    const val Q_TF = "20000000-0000-0000-0000-000000000009"
    const val Q_OPEN = "20000000-0000-0000-0000-000000000010"
    const val EC_BLOCK = "20000000-0000-0000-0000-000000000011"
    const val EC_1 = "20000000-0000-0000-0000-000000000012"

    /**
     * Six answerable units: two gap items (auto), one MC (auto), a TRUE_FALSE (auto) and an OPEN
     * question (review), one error correction (review). The heading, the SPEAKING free_sentences
     * and the non-interactive gap_fill are context only.
     */
    val EXERCISE_BLOCKS = """
        [
          { "id": "20000000-0000-0000-0000-000000000020", "type": "heading", "interactive": false, "text": "Haushalt", "level": 1 },
          { "id": "$GAP_BLOCK", "type": "gap_fill", "interactive": true, "instructions": "Ergänze.",
            "items": [
              { "id": "$GAP_1", "text": "Darum ___ du dich um die ___ kümmern.", "solution": { "answers": [["musst"], ["Wäsche"]] } },
              { "id": "$GAP_2", "text": "Er ___ nach Hause.", "solution": { "answers": [["geht", "läuft"]] } }
            ] },
          { "id": "$MC_BLOCK", "type": "multiple_choice", "interactive": true,
            "items": [{ "id": "$MC_1", "question": "Ich ___ geblieben.",
                        "options": [{ "id": "$OPT_HABE", "text": "habe" }, { "id": "$OPT_BIN", "text": "bin" }],
                        "solution": { "correctOptionIds": ["$OPT_BIN"] } }] },
          { "id": "$READING_BLOCK", "type": "reading", "interactive": true, "title": "Im Haushalt",
            "text": { "type": "doc", "content": [{ "type": "paragraph", "content": [{ "type": "text", "text": "Peter macht den Abwasch." }] }] },
            "questions": [
              { "id": "$Q_TF", "kind": "TRUE_FALSE", "question": "Peter spült.", "solution": { "isTrue": true } },
              { "id": "$Q_OPEN", "kind": "OPEN", "question": "Was macht Peter?", "solution": { "sampleAnswer": "Den Abwasch." } }
            ] },
          { "id": "$EC_BLOCK", "type": "error_correction", "interactive": true,
            "items": [{ "id": "$EC_1", "sentence": "Darum muss du dicht kümmern", "solution": { "corrected": "Darum musst du dich kümmern" } }] },
          { "id": "20000000-0000-0000-0000-000000000021", "type": "free_sentences", "interactive": true, "purpose": "SPEAKING",
            "items": [{ "id": "20000000-0000-0000-0000-000000000022", "prompt": "Was machst du am Wochenende?" }] },
          { "id": "20000000-0000-0000-0000-000000000023", "type": "gap_fill", "interactive": false,
            "items": [{ "id": "20000000-0000-0000-0000-000000000024", "text": "Ich ___ müde.", "solution": { "answers": [["bin"]] } }] }
        ]
    """.trimIndent()

    /** Creates the exercise document as the teacher and returns its id. */
    suspend fun createExerciseDocument(client: HttpClient, token: String, blocks: String = EXERCISE_BLOCKS): String {
        val response = client.post("/api/v1/documents") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{ "title": "Haushalt – Übungen", "audience": "STUDENT", "blocks": $blocks }""")
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    fun homeworkIdOf(assignment: AssignmentResponse, studentId: String): String =
        assignment.homework.single { it.student.id == studentId }.id
}
