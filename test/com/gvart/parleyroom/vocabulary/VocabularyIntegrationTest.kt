package com.gvart.parleyroom.vocabulary

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.vocabulary.data.VocabCategory
import com.gvart.parleyroom.vocabulary.data.VocabStatus
import com.gvart.parleyroom.vocabulary.transfer.CreateVocabularyWordRequest
import com.gvart.parleyroom.vocabulary.transfer.UpdateVocabularyWordRequest
import com.gvart.parleyroom.vocabulary.transfer.VocabularyPageResponse
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

class VocabularyIntegrationTest : IntegrationTest() {

    private suspend fun createWord(
        client: HttpClient,
        token: String,
        studentId: String = STUDENT_ID,
        german: String = "Hund",
        english: String = "Dog",
        category: VocabCategory = VocabCategory.NOUN,
    ): HttpResponse = client.post("/api/v1/vocabulary") {
        contentType(ContentType.Application.Json)
        bearerAuth(token)
        setBody(
            CreateVocabularyWordRequest(
                studentId = studentId,
                german = german,
                english = english,
                exampleSentence = "Der Hund ist groß.",
                exampleTranslation = "The dog is big.",
                category = category,
            )
        )
    }

    // -- Create --

    @Test
    fun `teacher can create vocabulary word for their student`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val response = createWord(client, token)

        assertEquals(HttpStatusCode.Created, response.status)
        val word = response.body<VocabularyWordResponse>()
        assertEquals("Hund", word.german)
        assertEquals("Dog", word.english)
        assertEquals(VocabStatus.NEW, word.status)
        assertEquals(STUDENT_ID, word.studentId)
        assertEquals(VocabCategory.NOUN, word.category)
        assertEquals(0, word.reviewCount)
        assertNotNull(word.id)
        assertNotNull(word.addedAt)
    }

    @Test
    fun `teacher cannot create vocabulary word for unrelated student`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        // Student2 has no teacher_students relationship with teacher
        val response = createWord(client, token, studentId = STUDENT_2_ID)

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `duplicate word for same student returns conflict`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        createWord(client, token)
        val response = createWord(client, token)

        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    // -- Get (list) --

    @Test
    fun `teacher sees only their students vocabulary words`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        // Create a word for the teacher's student
        createWord(client, teacherToken)

        val response = client.get("/api/v1/vocabulary") {
            bearerAuth(teacherToken)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val words = response.body<VocabularyPageResponse>().words
        assertEquals(1, words.size)
        assertEquals(STUDENT_ID, words[0].studentId)
    }

    @Test
    fun `student sees only their own vocabulary words`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        // Teacher creates a word for the student
        createWord(client, teacherToken)

        val response = client.get("/api/v1/vocabulary") {
            bearerAuth(studentToken)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val words = response.body<VocabularyPageResponse>().words
        assertEquals(1, words.size)
        assertEquals(STUDENT_ID, words[0].studentId)
    }

    @Test
    fun `student2 sees empty vocabulary list`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val student2Token = getStudent2Token(client)

        // Teacher creates a word for student (not student2)
        createWord(client, teacherToken)

        val response = client.get("/api/v1/vocabulary") {
            bearerAuth(student2Token)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val words = response.body<VocabularyPageResponse>().words
        assertEquals(0, words.size)
    }

    @Test
    fun `teacher cannot see other teachers students words - BUG-03 regression`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        // Create a word for the teacher's student
        createWord(client, teacherToken)

        // Student2 has no relationship with the teacher, so teacher should not see student2's words
        // Even if student2 had words from another teacher, this teacher should not see them
        val response = client.get("/api/v1/vocabulary") {
            bearerAuth(teacherToken)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val words = response.body<VocabularyPageResponse>().words
        // Teacher should only see words for their own students
        words.forEach { word ->
            assertEquals(STUDENT_ID, word.studentId)
        }
    }

    @Test
    fun `filter vocabulary words by studentId`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        createWord(client, teacherToken)

        val response = client.get("/api/v1/vocabulary?studentId=$STUDENT_ID") {
            bearerAuth(teacherToken)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val words = response.body<VocabularyPageResponse>().words
        assertEquals(1, words.size)
    }

    @Test
    fun `filter vocabulary words by status`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        createWord(client, teacherToken)

        val newWords = client.get("/api/v1/vocabulary?status=NEW") {
            bearerAuth(teacherToken)
        }.body<VocabularyPageResponse>().words
        assertEquals(1, newWords.size)

        val learnedWords = client.get("/api/v1/vocabulary?status=LEARNED") {
            bearerAuth(teacherToken)
        }.body<VocabularyPageResponse>().words
        assertEquals(0, learnedWords.size)
    }

    // -- Get (single) --

    @Test
    fun `get vocabulary word by id`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val created = createWord(client, token).body<VocabularyWordResponse>()

        val response = client.get("/api/v1/vocabulary/${created.id}") {
            bearerAuth(token)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(created.id, response.body<VocabularyWordResponse>().id)
    }

    @Test
    fun `get nonexistent vocabulary word returns 404`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val response = client.get("/api/v1/vocabulary/00000000-0000-0000-0000-000000000099") {
            bearerAuth(token)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // -- Update --

    @Test
    fun `teacher can update vocabulary word`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val created = createWord(client, token).body<VocabularyWordResponse>()

        val response = client.put("/api/v1/vocabulary/${created.id}") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(UpdateVocabularyWordRequest(english = "Doggy"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val updated = response.body<VocabularyWordResponse>()
        assertEquals("Doggy", updated.english)
        assertEquals("Hund", updated.german) // unchanged
    }

    @Test
    fun `student can update their own vocabulary word`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        val created = createWord(client, teacherToken).body<VocabularyWordResponse>()

        val response = client.put("/api/v1/vocabulary/${created.id}") {
            contentType(ContentType.Application.Json)
            bearerAuth(studentToken)
            setBody(UpdateVocabularyWordRequest(english = "Puppy"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Puppy", response.body<VocabularyWordResponse>().english)
    }

    // -- Delete --

    @Test
    fun `teacher can delete vocabulary word`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val created = createWord(client, token).body<VocabularyWordResponse>()

        val response = client.delete("/api/v1/vocabulary/${created.id}") {
            bearerAuth(token)
        }

        assertEquals(HttpStatusCode.NoContent, response.status)

        // Verify it's gone
        val getResponse = client.get("/api/v1/vocabulary/${created.id}") {
            bearerAuth(token)
        }
        assertEquals(HttpStatusCode.NotFound, getResponse.status)
    }

    @Test
    fun `student can delete their own vocabulary word`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        val created = createWord(client, teacherToken).body<VocabularyWordResponse>()

        val response = client.delete("/api/v1/vocabulary/${created.id}") {
            bearerAuth(studentToken)
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    // -- Review --

    @Test
    fun `review increments review count and updates status`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        val created = createWord(client, teacherToken).body<VocabularyWordResponse>()
        assertEquals(0, created.reviewCount)
        assertEquals(VocabStatus.NEW, created.status)

        val reviewed = client.post("/api/v1/vocabulary/${created.id}/review") {
            bearerAuth(studentToken)
        }

        assertEquals(HttpStatusCode.OK, reviewed.status)
        val word = reviewed.body<VocabularyWordResponse>()
        assertEquals(1, word.reviewCount)
        assertEquals(VocabStatus.REVIEW, word.status)
        assertNotNull(word.nextReviewAt)
    }

    // -- Authorization --

    @Test
    fun `unauthenticated request returns 401`() = testApp {
        val client = createJsonClient(this)

        val response = client.get("/api/v1/vocabulary")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `vocabulary list pagination returns slice and total`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        listOf("Hund", "Katze", "Vogel").forEach { german ->
            createWord(client, token, german = german)
        }

        val page = client.get("/api/v1/vocabulary?page=2&pageSize=2") {
            bearerAuth(token)
        }.body<VocabularyPageResponse>()

        assertEquals(3, page.total)
        assertEquals(2, page.page)
        assertEquals(2, page.pageSize)
        assertEquals(1, page.words.size)
    }
}
