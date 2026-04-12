package com.gvart.parleyroom.vocabulary

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.vocabulary.data.VocabCategory
import com.gvart.parleyroom.vocabulary.data.VocabStatus
import com.gvart.parleyroom.vocabulary.transfer.CreateVocabularyWordRequest
import com.gvart.parleyroom.vocabulary.transfer.UpdateVocabularyWordRequest
import com.gvart.parleyroom.vocabulary.transfer.VocabularyWordResponse
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VocabularyIntegrationTest : IntegrationTest() {

    private suspend fun createWord(
        client: HttpClient,
        token: String,
        german: String = "Haus",
        english: String = "house",
        studentId: String = STUDENT_ID,
    ): HttpResponse = client.post("/api/v1/vocabulary") {
        contentType(ContentType.Application.Json)
        bearerAuth(token)
        setBody(
            CreateVocabularyWordRequest(
                studentId = studentId,
                german = german,
                english = english,
                category = VocabCategory.NOUN,
            )
        )
    }

    // -- Create --

    @Test
    fun `student can add a vocabulary word`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val response = createWord(client, token)

        assertEquals(HttpStatusCode.Created, response.status)
        val word = response.body<VocabularyWordResponse>()
        assertEquals("Haus", word.german)
        assertEquals("house", word.english)
        assertEquals(VocabCategory.NOUN, word.category)
        assertEquals(VocabStatus.NEW, word.status)
        assertEquals(0, word.reviewCount)
        assertEquals(STUDENT_ID, word.studentId)
    }

    @Test
    fun `teacher can add word for their student`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val response = createWord(client, token)

        assertEquals(HttpStatusCode.Created, response.status)
    }

    @Test
    fun `cannot add duplicate word for same student`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        createWord(client, token)
        val response = createWord(client, token)

        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test
    fun `student cannot add word for another student`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val response = createWord(client, token, studentId = STUDENT_2_ID)

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // -- Get --

    @Test
    fun `student can get their words`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        createWord(client, token, german = "Haus")
        createWord(client, token, german = "Baum", english = "tree")

        val response = client.get("/api/v1/vocabulary") {
            bearerAuth(token)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val words = response.body<List<VocabularyWordResponse>>()
        assertEquals(2, words.size)
    }

    @Test
    fun `student can get word by id`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val created = createWord(client, token).body<VocabularyWordResponse>()

        val response = client.get("/api/v1/vocabulary/${created.id}") {
            bearerAuth(token)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val word = response.body<VocabularyWordResponse>()
        assertEquals(created.id, word.id)
    }

    @Test
    fun `filter words by status`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val word = createWord(client, token).body<VocabularyWordResponse>()

        // Review it to change status
        client.post("/api/v1/vocabulary/${word.id}/review") {
            bearerAuth(token)
        }

        val newWords = client.get("/api/v1/vocabulary?status=NEW") {
            bearerAuth(token)
        }.body<List<VocabularyWordResponse>>()
        assertEquals(0, newWords.size)

        val reviewWords = client.get("/api/v1/vocabulary?status=REVIEW") {
            bearerAuth(token)
        }.body<List<VocabularyWordResponse>>()
        assertEquals(1, reviewWords.size)
    }

    // -- Update --

    @Test
    fun `student can update their word`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val created = createWord(client, token).body<VocabularyWordResponse>()

        val response = client.put("/api/v1/vocabulary/${created.id}") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(UpdateVocabularyWordRequest(english = "building"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val updated = response.body<VocabularyWordResponse>()
        assertEquals("building", updated.english)
        assertEquals("Haus", updated.german) // unchanged
    }

    // -- Delete --

    @Test
    fun `student can delete their word`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val created = createWord(client, token).body<VocabularyWordResponse>()

        val response = client.delete("/api/v1/vocabulary/${created.id}") {
            bearerAuth(token)
        }

        assertEquals(HttpStatusCode.NoContent, response.status)

        val getResponse = client.get("/api/v1/vocabulary/${created.id}") {
            bearerAuth(token)
        }
        assertEquals(HttpStatusCode.NotFound, getResponse.status)
    }

    // -- Review --

    @Test
    fun `review increments count and changes status`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val created = createWord(client, token).body<VocabularyWordResponse>()
        assertEquals(VocabStatus.NEW, created.status)

        val reviewed = client.post("/api/v1/vocabulary/${created.id}/review") {
            bearerAuth(token)
        }.body<VocabularyWordResponse>()

        assertEquals(1, reviewed.reviewCount)
        assertEquals(VocabStatus.REVIEW, reviewed.status)
        assertNotNull(reviewed.nextReviewAt)
    }

    @Test
    fun `word becomes learned after enough reviews`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val created = createWord(client, token).body<VocabularyWordResponse>()

        var word = created
        repeat(5) {
            word = client.post("/api/v1/vocabulary/${created.id}/review") {
                bearerAuth(token)
            }.body<VocabularyWordResponse>()
        }

        assertEquals(5, word.reviewCount)
        assertEquals(VocabStatus.LEARNED, word.status)
    }

    // -- Authorization --

    @Test
    fun `student2 cannot access student1 word`() = testApp {
        val client = createJsonClient(this)
        val studentToken = getStudentToken(client)
        val student2Token = getStudent2Token(client)

        val created = createWord(client, studentToken).body<VocabularyWordResponse>()

        val response = client.get("/api/v1/vocabulary/${created.id}") {
            bearerAuth(student2Token)
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }
}
