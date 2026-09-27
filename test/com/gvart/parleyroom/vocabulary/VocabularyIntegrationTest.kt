package com.gvart.parleyroom.vocabulary

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.group.data.GroupType
import com.gvart.parleyroom.group.transfer.GroupRequest
import com.gvart.parleyroom.group.transfer.GroupResponse
import com.gvart.parleyroom.lesson.transfer.CreateLessonRequest
import com.gvart.parleyroom.lesson.transfer.LessonResponse
import com.gvart.parleyroom.topic.transfer.CreateTopicRequest
import com.gvart.parleyroom.topic.transfer.TopicResponse
import com.gvart.parleyroom.vocabulary.data.NounArticle
import com.gvart.parleyroom.vocabulary.data.StudentVocabStatus
import com.gvart.parleyroom.vocabulary.data.WordType
import com.gvart.parleyroom.vocabulary.transfer.AssignVocabRequest
import com.gvart.parleyroom.vocabulary.transfer.AssignVocabResponse
import com.gvart.parleyroom.vocabulary.transfer.QuickAddVocabRequest
import com.gvart.parleyroom.vocabulary.transfer.QuickAddVocabResponse
import com.gvart.parleyroom.vocabulary.transfer.SetStudentLevelRequest
import com.gvart.parleyroom.vocabulary.transfer.StudentVocabPageResponse
import com.gvart.parleyroom.vocabulary.transfer.StudentVocabResponse
import com.gvart.parleyroom.vocabulary.transfer.UpdateStudentVocabRequest
import com.gvart.parleyroom.vocabulary.transfer.VocabDisplaySetting
import com.gvart.parleyroom.vocabulary.transfer.VocabEntryInput
import com.gvart.parleyroom.vocabulary.transfer.VocabEntryPageResponse
import com.gvart.parleyroom.vocabulary.transfer.VocabEntryResponse
import com.gvart.parleyroom.vocabulary.transfer.VocabSettingsResponse
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
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VocabularyIntegrationTest : IntegrationTest() {

    private val wort = VocabEntryInput(
        lemma = "Wort",
        article = NounArticle.DAS,
        plural = "Wörter",
        wordType = WordType.NOUN,
        translations = mapOf("ru" to "слово", "en" to "word"),
        explanationDe = "kleinste sprachliche Einheit",
        exampleSentence = "Das Wort ist neu.",
    )

    private suspend fun quickAdd(
        client: HttpClient,
        token: String,
        entry: VocabEntryInput = wort,
        studentIds: List<String> = listOf(STUDENT_ID),
        groupId: String? = null,
        lessonId: String? = null,
    ): HttpResponse = client.post("/api/v1/vocabulary") {
        contentType(ContentType.Application.Json)
        bearerAuth(token)
        setBody(QuickAddVocabRequest(entry = entry, studentIds = studentIds, groupId = groupId, lessonId = lessonId))
    }

    private suspend fun createEntry(client: HttpClient, token: String, entry: VocabEntryInput = wort): HttpResponse =
        client.post("/api/v1/vocab-entries") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(entry)
        }

    private suspend fun studentWords(client: HttpClient, token: String, query: String = ""): List<StudentVocabResponse> =
        client.get("/api/v1/vocabulary$query") { bearerAuth(token) }.body<StudentVocabPageResponse>().words

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

    // -- Quick-add / library --

    @Test
    fun `quick-add creates a library entry and assigns it to the student`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val response = quickAdd(client, token)
        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.body<QuickAddVocabResponse>()
        assertFalse(body.reused)
        assertEquals(1, body.assigned)
        assertEquals("Wort", body.entry.lemma)
        assertEquals(NounArticle.DAS, body.entry.article)
        assertEquals("Wörter", body.entry.plural)

        val words = studentWords(client, getStudentToken(client))
        assertEquals(1, words.size)
        assertEquals(StudentVocabStatus.NEW, words.single().status)
        assertEquals(body.entry.id, words.single().entryId)
    }

    @Test
    fun `quick-add of the same word reuses the entry and skips existing students`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val first = quickAdd(client, token).body<QuickAddVocabResponse>()
        val second = quickAdd(client, token, entry = wort.copy(lemma = "wort")).body<QuickAddVocabResponse>()

        assertTrue(second.reused)
        assertEquals(first.entry.id, second.entry.id)
        assertEquals(0, second.assigned)
        assertEquals(1, second.skipped)
    }

    @Test
    fun `quick-add for an unrelated student is rejected`() = testApp {
        val client = createJsonClient(this)
        val response = quickAdd(client, getTeacherToken(client), studentIds = listOf(STUDENT_2_ID))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("STUDENT_NOT_LINKED", response.body<ProblemDetail>().code)
    }

    @Test
    fun `quick-add in a lesson assigns the lesson's students and links the word`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val lessonId = createLesson(client, token)

        val body = quickAdd(client, token, studentIds = emptyList(), lessonId = lessonId).body<QuickAddVocabResponse>()
        assertEquals(1, body.assigned)
        assertEquals(lessonId, body.entry.sourceLessonId)

        val words = studentWords(client, token, "?lessonId=$lessonId")
        assertEquals(listOf(STUDENT_ID), words.map { it.studentId })
    }

    @Test
    fun `duplicate library entry returns VOCAB_ENTRY_DUPLICATE but a different word type is fine`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        assertEquals(HttpStatusCode.Created, createEntry(client, token).status)
        val dup = createEntry(client, token)
        assertEquals(HttpStatusCode.Conflict, dup.status)
        assertEquals("VOCAB_ENTRY_DUPLICATE", dup.body<ProblemDetail>().code)

        val essen = createEntry(client, token, VocabEntryInput(lemma = "essen", wordType = WordType.VERB))
        val dasEssen = createEntry(client, token, VocabEntryInput(lemma = "Essen", article = NounArticle.DAS, wordType = WordType.NOUN))
        assertEquals(HttpStatusCode.Created, essen.status)
        assertEquals(HttpStatusCode.Created, dasEssen.status)

        val lookup = client.get("/api/v1/vocab-entries/lookup?lemma=ESSEN") { bearerAuth(token) }.body<List<VocabEntryResponse>>()
        assertEquals(2, lookup.size)
        val nounOnly = client.get("/api/v1/vocab-entries/lookup?lemma=essen&wordType=NOUN") { bearerAuth(token) }
            .body<List<VocabEntryResponse>>()
        assertEquals("Essen", nounOnly.single().lemma)
    }

    @Test
    fun `unsupported translation language is rejected`() = testApp {
        val client = createJsonClient(this)
        val response = createEntry(client, getTeacherToken(client), wort.copy(translations = mapOf("xx" to "?")))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("VOCAB_LANGUAGE_UNSUPPORTED", response.body<ProblemDetail>().code)
    }

    @Test
    fun `article is only allowed on nouns`() = testApp {
        val client = createJsonClient(this)
        val response = createEntry(client, getTeacherToken(client), VocabEntryInput(lemma = "gehen", article = NounArticle.DER, wordType = WordType.VERB))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("VALIDATION_FAILED", response.body<ProblemDetail>().code)
    }

    @Test
    fun `teacher edits an entry and filters the library by topic`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val topicId = client.post("/api/v1/topics") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(CreateTopicRequest(name = "Sprache"))
        }.body<TopicResponse>().id
        val entryId = createEntry(client, token).body<VocabEntryResponse>().id
        createEntry(client, token, VocabEntryInput(lemma = "Haus", article = NounArticle.DAS, wordType = WordType.NOUN))

        val updated = client.put("/api/v1/vocab-entries/$entryId") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(wort.copy(topicIds = listOf(topicId), level = LanguageLevel.A2, synonyms = listOf("Begriff")))
        }.body<VocabEntryResponse>()
        assertEquals(listOf(topicId), updated.topicIds)
        assertEquals(listOf("Begriff"), updated.synonyms)

        val page = client.get("/api/v1/vocab-entries?topicId=$topicId") { bearerAuth(token) }.body<VocabEntryPageResponse>()
        assertEquals(listOf("Wort"), page.entries.map { it.lemma })
        val all = client.get("/api/v1/vocab-entries?q=au") { bearerAuth(token) }.body<VocabEntryPageResponse>()
        assertEquals(listOf("Haus"), all.entries.map { it.lemma })
    }

    @Test
    fun `students cannot browse or write the library`() = testApp {
        val client = createJsonClient(this)
        val studentToken = getStudentToken(client)

        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/vocab-entries") { bearerAuth(studentToken) }.status)
        assertEquals(HttpStatusCode.Forbidden, createEntry(client, studentToken).status)
        assertEquals(HttpStatusCode.Forbidden, quickAdd(client, studentToken).status)
    }

    @Test
    fun `assigning an entry to a group adds it to every member`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val groupId = client.post("/api/v1/groups") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(GroupRequest(name = "Club", type = GroupType.SPEECH, studentIds = listOf(STUDENT_ID)))
        }.body<GroupResponse>().id
        val entryId = createEntry(client, token).body<VocabEntryResponse>().id

        val result = client.post("/api/v1/vocab-entries/$entryId/assign") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(AssignVocabRequest(groupId = groupId))
        }.body<AssignVocabResponse>()

        assertEquals(AssignVocabResponse(assigned = 1, skipped = 0), result)
        assertEquals(1, studentWords(client, getStudentToken(client)).size)
    }

    @Test
    fun `deleting an entry removes it from students`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val entryId = quickAdd(client, token).body<QuickAddVocabResponse>().entry.id

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/vocab-entries/$entryId") { bearerAuth(token) }.status)
        assertEquals(0, studentWords(client, getStudentToken(client)).size)
        val missing = client.get("/api/v1/vocab-entries/$entryId") { bearerAuth(token) }
        assertEquals("VOCAB_ENTRY_NOT_FOUND", missing.body<ProblemDetail>().code)
    }

    // -- Student vocabulary --

    @Test
    fun `list visibility follows teacher-student relationships`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        quickAdd(client, token)

        assertEquals(1, studentWords(client, token).size)
        assertEquals(1, studentWords(client, getStudentToken(client)).size)
        assertEquals(0, studentWords(client, getStudent2Token(client)).size)
        assertEquals(1, studentWords(client, getAdminToken(client)).size)

        val forbidden = client.get("/api/v1/vocabulary?studentId=$STUDENT_ID") { bearerAuth(getStudent2Token(client)) }
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
    }

    @Test
    fun `status filter, status update and removal`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        quickAdd(client, token)
        quickAdd(client, token, entry = VocabEntryInput(lemma = "gehen", wordType = WordType.VERB))
        val id = studentWords(client, token).first { it.lemma == "gehen" }.id

        val updated = client.put("/api/v1/vocabulary/$id") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(UpdateStudentVocabRequest(StudentVocabStatus.LEARNED))
        }.body<StudentVocabResponse>()
        assertEquals(StudentVocabStatus.LEARNED, updated.status)
        assertEquals(listOf("gehen"), studentWords(client, token, "?status=LEARNED").map { it.lemma })

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/vocabulary/$id") { bearerAuth(token) }.status)
        assertEquals(listOf("Wort"), studentWords(client, token).map { it.lemma })
        val missing = client.get("/api/v1/vocabulary/$id") { bearerAuth(token) }
        assertEquals("VOCABULARY_WORD_NOT_FOUND", missing.body<ProblemDetail>().code)
    }

    @Test
    fun `review schedules the next review on the FSRS columns`() = testApp {
        val client = createJsonClient(this)
        quickAdd(client, getTeacherToken(client))
        val studentToken = getStudentToken(client)
        val id = studentWords(client, studentToken).single().id

        val first = client.post("/api/v1/vocabulary/$id/review") { bearerAuth(studentToken) }.body<StudentVocabResponse>()
        assertEquals(1, first.reps)
        assertEquals(StudentVocabStatus.LEARNING, first.status)
        assertNotNull(first.lastReview)
        assertTrue(first.due!!.isAfter(OffsetDateTime.now().plusDays(1)))

        repeat(4) { client.post("/api/v1/vocabulary/$id/review") { bearerAuth(studentToken) } }
        val learned = client.get("/api/v1/vocabulary/$id") { bearerAuth(studentToken) }.body<StudentVocabResponse>()
        assertEquals(5, learned.reps)
        assertEquals(StudentVocabStatus.LEARNED, learned.status)
    }

    // -- Display settings --

    @Test
    fun `without a level the student sees only the Russian translation`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        quickAdd(client, token)

        val word = studentWords(client, getStudentToken(client)).single()
        assertEquals(listOf("ru"), word.display.fields)
        assertEquals(mapOf("ru" to "слово"), word.translations)
        assertNull(word.explanationDe)
        assertNull(word.revealTranslations)

        // Teachers always see every field
        val teacherView = studentWords(client, token).single()
        assertEquals(mapOf("ru" to "слово", "en" to "word"), teacherView.translations)
        assertEquals("kleinste sprachliche Einheit", teacherView.explanationDe)
    }

    @Test
    fun `B1 student gets the German explanation with revealable translations`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        quickAdd(client, token)

        val settings = client.put("/api/v1/students/$STUDENT_ID/level") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(SetStudentLevelRequest(LanguageLevel.B1))
        }.body<VocabSettingsResponse>()
        assertEquals(LanguageLevel.B1, settings.level)
        assertEquals(listOf("de_explanation"), settings.fields)
        assertTrue(settings.allowTranslationToggle)
        assertTrue(settings.isDefault)

        val word = studentWords(client, getStudentToken(client)).single()
        assertEquals(emptyMap(), word.translations)
        assertEquals("kleinste sprachliche Einheit", word.explanationDe)
        assertEquals(mapOf("ru" to "слово", "en" to "word"), word.revealTranslations)
    }

    @Test
    fun `explicit setting overrides the level default and can be reset`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        quickAdd(client, token)

        val put = client.put("/api/v1/students/$STUDENT_ID/vocab-settings") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(VocabDisplaySetting(fields = listOf("en", "de_explanation"), allowTranslationToggle = false))
        }.body<VocabSettingsResponse>()
        assertFalse(put.isDefault)

        val word = studentWords(client, getStudentToken(client)).single()
        assertEquals(mapOf("en" to "word"), word.translations)
        assertEquals("kleinste sprachliche Einheit", word.explanationDe)
        assertNull(word.revealTranslations)

        val own = client.get("/api/v1/students/$STUDENT_ID/vocab-settings") { bearerAuth(getStudentToken(client)) }
            .body<VocabSettingsResponse>()
        assertEquals(listOf("en", "de_explanation"), own.fields)
        assertEquals(TEACHER_ID, own.teacherId)

        val reset = client.delete("/api/v1/students/$STUDENT_ID/vocab-settings") { bearerAuth(token) }.body<VocabSettingsResponse>()
        assertTrue(reset.isDefault)
        assertEquals(listOf("ru"), reset.fields)
    }

    @Test
    fun `unsupported display field is rejected`() = testApp {
        val client = createJsonClient(this)
        val response = client.put("/api/v1/students/$STUDENT_ID/vocab-settings") {
            contentType(ContentType.Application.Json)
            bearerAuth(getTeacherToken(client))
            setBody(VocabDisplaySetting(fields = listOf("fr"), allowTranslationToggle = false))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("VOCAB_DISPLAY_FIELD_UNSUPPORTED", response.body<ProblemDetail>().code)
    }

    @Test
    fun `students cannot change settings or level`() = testApp {
        val client = createJsonClient(this)
        val studentToken = getStudentToken(client)

        val settings = client.put("/api/v1/students/$STUDENT_ID/vocab-settings") {
            contentType(ContentType.Application.Json)
            bearerAuth(studentToken)
            setBody(VocabDisplaySetting(fields = listOf("en"), allowTranslationToggle = true))
        }
        val level = client.put("/api/v1/students/$STUDENT_ID/level") {
            contentType(ContentType.Application.Json)
            bearerAuth(studentToken)
            setBody(SetStudentLevelRequest(LanguageLevel.C2))
        }
        assertEquals(HttpStatusCode.Forbidden, settings.status)
        assertEquals(HttpStatusCode.Forbidden, level.status)

        val unrelated = client.put("/api/v1/students/$STUDENT_2_ID/level") {
            contentType(ContentType.Application.Json)
            bearerAuth(getTeacherToken(client))
            setBody(SetStudentLevelRequest(LanguageLevel.B2))
        }
        assertEquals(HttpStatusCode.Forbidden, unrelated.status)
    }
}
