package com.gvart.parleyroom.lesson

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.lesson.transfer.CancelLessonRequest
import com.gvart.parleyroom.lesson.transfer.CorrectedSentenceInput
import com.gvart.parleyroom.lesson.transfer.CreateLessonRequest
import com.gvart.parleyroom.lesson.transfer.LessonPageResponse
import com.gvart.parleyroom.lesson.transfer.LessonResponse
import com.gvart.parleyroom.lesson.transfer.RescheduleLessonRequest
import com.gvart.parleyroom.lesson.transfer.UpdateLessonContentRequest
import com.gvart.parleyroom.topic.transfer.CreateTopicRequest
import com.gvart.parleyroom.topic.transfer.GrammarTopicRequest
import com.gvart.parleyroom.topic.transfer.GrammarTopicResponse
import com.gvart.parleyroom.topic.transfer.TopicResponse
import com.gvart.parleyroom.vocabulary.data.NounArticle
import com.gvart.parleyroom.vocabulary.data.WordType
import com.gvart.parleyroom.vocabulary.transfer.QuickAddVocabRequest
import com.gvart.parleyroom.vocabulary.transfer.QuickAddVocabResponse
import com.gvart.parleyroom.vocabulary.transfer.StudentVocabPageResponse
import com.gvart.parleyroom.vocabulary.transfer.VocabDisplaySetting
import com.gvart.parleyroom.vocabulary.transfer.VocabEntryInput
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
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LessonContentIntegrationTest : IntegrationTest() {

    private suspend fun createLesson(client: HttpClient, token: String): String =
        client.post("/api/v1/lessons") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(
                CreateLessonRequest(
                    teacherId = TEACHER_ID,
                    studentIds = listOf(STUDENT_ID),
                    title = "Lektion",
                    type = LessonType.ONE_ON_ONE,
                    scheduledAt = OffsetDateTime.now().plusDays(2),
                    topic = "Alltag",
                )
            )
        }.body<LessonResponse>().id

    private suspend fun patchContent(
        client: HttpClient,
        token: String,
        lessonId: String,
        request: UpdateLessonContentRequest,
    ): HttpResponse = client.patch("/api/v1/lessons/$lessonId/content") {
        contentType(ContentType.Application.Json)
        bearerAuth(token)
        setBody(request)
    }

    @Test
    fun `teacher records lesson content and the lesson detail returns it`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val lessonId = createLesson(client, token)
        val topicId = client.post("/api/v1/topics") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(CreateTopicRequest(name = "Haushalt"))
        }.body<TopicResponse>().id
        val grammarId = client.post("/api/v1/grammar-topics") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(GrammarTopicRequest(name = "Perfekt", level = LanguageLevel.A2))
        }.body<GrammarTopicResponse>().id
        val entryId = client.post("/api/v1/vocabulary") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(QuickAddVocabRequest(entry = VocabEntryInput(lemma = "Wäsche", article = NounArticle.DIE, wordType = WordType.NOUN)))
        }.body<QuickAddVocabResponse>().entry.id

        val response = patchContent(
            client, token, lessonId,
            UpdateLessonContentRequest(
                rawNotes = "Wäsche waschen, Perfekt mit sein",
                promptUsed = "Erstelle eine Übersicht",
                topicIds = listOf(topicId),
                grammarTopicIds = listOf(grammarId),
                vocabEntryIds = listOf(entryId),
                correctedSentences = listOf(CorrectedSentenceInput("Ich habe geblieben", "Ich bin geblieben")),
            ),
        )
        assertEquals(HttpStatusCode.OK, response.status)

        val teacherView = client.get("/api/v1/lessons/$lessonId") { bearerAuth(token) }.body<LessonResponse>()
        assertEquals("Wäsche waschen, Perfekt mit sein", teacherView.rawNotes)
        assertEquals("Erstelle eine Übersicht", teacherView.promptUsed)
        assertEquals(listOf("Haushalt"), teacherView.topics.map { it.name })
        assertEquals(listOf("Perfekt"), teacherView.grammarTopics.map { it.name })
        assertEquals(listOf("Wäsche"), teacherView.vocab.map { it.lemma })
        assertEquals("Ich bin geblieben", teacherView.correctedSentences.single().correct)

        val studentToken = getStudentToken(client)
        val studentView = client.get("/api/v1/lessons/$lessonId") { bearerAuth(studentToken) }.body<LessonResponse>()
        assertNull(studentView.rawNotes)
        assertNull(studentView.promptUsed)
        assertEquals(1, studentView.topics.size)
        assertEquals(1, studentView.vocab.size)

        val studentList = client.get("/api/v1/lessons") { bearerAuth(studentToken) }.body<LessonPageResponse>()
        assertNull(studentList.lessons.single { it.id == lessonId }.rawNotes)
    }

    @Test
    fun `lists replace existing links and null leaves them untouched`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val lessonId = createLesson(client, token)
        val topicId = client.post("/api/v1/topics") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(CreateTopicRequest(name = "Reisen"))
        }.body<TopicResponse>().id

        patchContent(client, token, lessonId, UpdateLessonContentRequest(topicIds = listOf(topicId)))
        val untouched = patchContent(client, token, lessonId, UpdateLessonContentRequest(rawNotes = "x")).body<LessonResponse>()
        assertEquals(1, untouched.topics.size)

        val cleared = patchContent(client, token, lessonId, UpdateLessonContentRequest(topicIds = emptyList())).body<LessonResponse>()
        assertEquals(0, cleared.topics.size)
        assertEquals("x", cleared.rawNotes)
    }

    @Test
    fun `unknown topic is rejected`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val lessonId = createLesson(client, token)

        val response = patchContent(
            client, token, lessonId,
            UpdateLessonContentRequest(topicIds = listOf("00000000-0000-0000-0000-00000000beef")),
        )
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("TOPIC_NOT_FOUND", response.body<ProblemDetail>().code)
    }

    @Test
    fun `students cannot edit lesson content`() = testApp {
        val client = createJsonClient(this)
        val lessonId = createLesson(client, getTeacherToken(client))

        val response = patchContent(client, getStudentToken(client), lessonId, UpdateLessonContentRequest(rawNotes = "hi"))
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `lesson vocab display override applies to words from that lesson`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val lessonId = createLesson(client, token)
        client.post("/api/v1/vocabulary") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(
                QuickAddVocabRequest(
                    entry = VocabEntryInput(
                        lemma = "Wäsche", article = NounArticle.DIE, wordType = WordType.NOUN,
                        translations = mapOf("ru" to "бельё", "en" to "laundry"),
                    ),
                    lessonId = lessonId,
                )
            )
        }

        val override = client.put("/api/v1/lessons/$lessonId/vocab-display") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(VocabDisplaySetting(fields = listOf("en"), allowTranslationToggle = false))
        }.body<LessonResponse>()
        assertEquals(listOf("en"), override.vocabDisplayOverride?.fields)

        val studentToken = getStudentToken(client)
        val word = client.get("/api/v1/vocabulary") { bearerAuth(studentToken) }.body<StudentVocabPageResponse>().words.single()
        assertEquals(mapOf("en" to "laundry"), word.translations)

        val cleared = client.delete("/api/v1/lessons/$lessonId/vocab-display") { bearerAuth(token) }.body<LessonResponse>()
        assertNull(cleared.vocabDisplayOverride)
        val after = client.get("/api/v1/vocabulary") { bearerAuth(studentToken) }.body<StudentVocabPageResponse>().words.single()
        assertEquals(mapOf("ru" to "бельё"), after.translations)
    }

    @Test
    fun `students never receive raw notes or prompt from any lesson endpoint`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val studentToken = getStudentToken(client)
        val lessonId = createLesson(client, token)
        patchContent(client, token, lessonId, UpdateLessonContentRequest(rawNotes = "private", promptUsed = "secret"))

        fun assertHidden(lesson: LessonResponse) {
            assertNull(lesson.rawNotes)
            assertNull(lesson.promptUsed)
        }

        assertHidden(client.get("/api/v1/lessons/$lessonId") { bearerAuth(studentToken) }.body())
        client.get("/api/v1/lessons") { bearerAuth(studentToken) }.body<LessonPageResponse>().lessons.forEach(::assertHidden)

        // Teacher proposes a reschedule; the student's accept returns the lesson
        client.post("/api/v1/lessons/$lessonId/reschedule") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(RescheduleLessonRequest(newScheduledAt = OffsetDateTime.now().plusDays(4)))
        }
        val accepted = client.post("/api/v1/lessons/$lessonId/reschedule/accept") { bearerAuth(studentToken) }
        assertEquals(HttpStatusCode.OK, accepted.status)
        assertHidden(accepted.body())

        val cancelled = client.post("/api/v1/lessons/$lessonId/cancel") {
            contentType(ContentType.Application.Json)
            bearerAuth(studentToken)
            setBody(CancelLessonRequest())
        }
        assertEquals(HttpStatusCode.OK, cancelled.status)
        assertHidden(cancelled.body())

        // The teacher still sees them
        val teacherView = client.get("/api/v1/lessons/$lessonId") { bearerAuth(token) }.body<LessonResponse>()
        assertEquals("private", teacherView.rawNotes)
        assertEquals("secret", teacherView.promptUsed)
    }
}
