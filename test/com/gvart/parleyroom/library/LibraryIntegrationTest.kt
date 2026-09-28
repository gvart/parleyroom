package com.gvart.parleyroom.library

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.ai.STUDENT
import com.gvart.parleyroom.ai.STUDENT_2
import com.gvart.parleyroom.ai.seedLesson
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.lesson.data.LessonStudentStatus
import com.gvart.parleyroom.lesson.data.LessonStudentTable
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.library.transfer.CoverageSource
import com.gvart.parleyroom.library.transfer.GrammarLevelGroup
import com.gvart.parleyroom.library.transfer.GrammarTopicLibrary
import com.gvart.parleyroom.library.transfer.LevelCounts
import com.gvart.parleyroom.library.transfer.LibrarySummary
import com.gvart.parleyroom.library.transfer.SubtreeCounts
import com.gvart.parleyroom.library.transfer.TopicLibrary
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryIntegrationTest : IntegrationTest() {

    private fun setStatus(lessonId: UUID, status: LessonStatus) = transaction {
        LessonTable.update({ LessonTable.id eq lessonId }) { it[LessonTable.status] = status }
    }

    private fun addParticipant(lessonId: UUID, studentId: UUID, status: LessonStudentStatus) = transaction {
        LessonStudentTable.insert {
            it[LessonStudentTable.lessonId] = lessonId
            it[LessonStudentTable.studentId] = studentId
            it[LessonStudentTable.status] = status
        }
    }

    @Test
    fun `summary counts per level and per topic with direct and subtree totals`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val alltag = LibraryFixtures.topic("Alltag", levels = listOf(LanguageLevel.A2, LanguageLevel.B1))
        val haushalt = LibraryFixtures.topic("Haushalt", parentId = alltag)
        val garten = LibraryFixtures.topic("Garten", parentId = haushalt, levels = listOf(LanguageLevel.B1))
        LibraryFixtures.entry("Gießkanne", topics = listOf(haushalt), level = LanguageLevel.A2)
        LibraryFixtures.entry("Rasen", topics = listOf(garten, alltag), level = LanguageLevel.B1)
        LibraryFixtures.entry("ohne Thema")
        LibraryFixtures.document("Haushalt – Übung", topics = listOf(haushalt), level = LanguageLevel.A2)
        LibraryFixtures.material("Gartenbild", topics = listOf(garten))
        LibraryFixtures.grammar("Perfekt", LanguageLevel.A2)
        LibraryFixtures.grammar("Aussprache")
        val lesson = seedLesson(students = listOf(STUDENT))
        LibraryFixtures.tagLesson(lesson, topics = listOf(haushalt))
        // Another teacher's library never shows up.
        val other = LibraryFixtures.otherTeacher()
        LibraryFixtures.entry("Fremd", topics = listOf(LibraryFixtures.topic("Fremd", teacherId = other)), level = LanguageLevel.A2, teacherId = other)

        val summary = client.get("/api/v1/library/summary") { bearerAuth(token) }.body<LibrarySummary>()

        assertEquals(7, summary.levels.size)
        assertEquals(LevelCounts(LanguageLevel.A2, words = 1, documents = 1, materials = 0, topics = 1, grammarTopics = 1), summary.levels.single { it.level == LanguageLevel.A2 })
        assertEquals(LevelCounts(LanguageLevel.B1, words = 1, documents = 0, materials = 0, topics = 2, grammarTopics = 0), summary.levels.single { it.level == LanguageLevel.B1 })
        assertEquals(LevelCounts(null, words = 1, documents = 0, materials = 1, topics = 1, grammarTopics = 1), summary.levels.single { it.level == null })
        assertEquals(3L, summary.totals.topics)
        assertEquals(3L, summary.totals.words)

        val byId = summary.topics.associateBy { it.topicId }
        assertEquals(3, byId.size)
        val a = byId.getValue(alltag.toString())
        assertEquals(1L, a.words)
        assertEquals(0L, a.lessons)
        // Rasen is tagged in Alltag and Garten: counted once in the subtree.
        assertEquals(SubtreeCounts(words = 2, documents = 1, materials = 1), a.subtree)
        val h = byId.getValue(haushalt.toString())
        assertEquals(listOf(1L, 1L, 0L, 1L, 1L), listOf(h.words, h.documents, h.materials, h.lessons, h.coveredStudents))
        assertEquals(SubtreeCounts(words = 2, documents = 1, materials = 1), h.subtree)
        assertEquals(SubtreeCounts(words = 1, documents = 0, materials = 1), byId.getValue(garten.toString()).subtree)

        val b1 = client.get("/api/v1/library/summary?level=B1") { bearerAuth(token) }.body<LibrarySummary>()
        val b1Alltag = b1.topics.single { it.topicId == alltag.toString() }
        assertEquals(SubtreeCounts(words = 1, documents = 0, materials = 0), b1Alltag.subtree)
        assertEquals(0L, b1.topics.single { it.topicId == haushalt.toString() }.words)
        assertEquals(summary.levels, b1.levels)
    }

    @Test
    fun `summary stays correct for many topics`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val root = LibraryFixtures.topic("Wurzel")
        val ids = (1..40).map { i -> LibraryFixtures.topic("Thema $i", parentId = root).also { LibraryFixtures.entry("Wort $i", topics = listOf(it)) } }

        val summary = client.get("/api/v1/library/summary") { bearerAuth(token) }.body<LibrarySummary>()
        assertEquals(41, summary.topics.size)
        assertEquals(40L, summary.topics.single { it.topicId == root.toString() }.subtree.words)
        ids.forEach { id -> assertEquals(1L, summary.topics.single { it.topicId == id.toString() }.words) }
    }

    @Test
    fun `topic view lists content, path, children and who covered it`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val alltag = LibraryFixtures.topic("Alltag")
        val haushalt = LibraryFixtures.topic("Haushalt", parentId = alltag)
        LibraryFixtures.topic("Küche", parentId = haushalt)
        val entry = LibraryFixtures.entry("Gießkanne", topics = listOf(haushalt), level = LanguageLevel.A2)
        LibraryFixtures.entry("Besen", topics = listOf(haushalt), level = LanguageLevel.B1)
        LibraryFixtures.document("Übung", topics = listOf(haushalt), level = LanguageLevel.A2)
        LibraryFixtures.material("Bild", topics = listOf(haushalt))

        val held = OffsetDateTime.now().minusDays(3).truncatedTo(ChronoUnit.SECONDS)
        val lesson = seedLesson(students = listOf(STUDENT), scheduledAt = held)
        addParticipant(lesson, STUDENT_2, LessonStudentStatus.REQUESTED)
        LibraryFixtures.tagLesson(lesson, topics = listOf(haushalt))
        val cancelled = seedLesson(students = listOf(STUDENT_2)).also { setStatus(it, LessonStatus.CANCELLED) }
        LibraryFixtures.tagLesson(cancelled, topics = listOf(haushalt))
        val future = seedLesson(students = listOf(STUDENT_2), scheduledAt = OffsetDateTime.now().plusDays(2))
        setStatus(future, LessonStatus.CONFIRMED)
        LibraryFixtures.tagLesson(future, topics = listOf(haushalt))
        val added = OffsetDateTime.now().minusDays(1).truncatedTo(ChronoUnit.SECONDS)
        LibraryFixtures.assign(STUDENT, entry, addedAt = added)

        val view = client.get("/api/v1/library/topics/$haushalt") { bearerAuth(token) }.body<TopicLibrary>()
        assertEquals("Haushalt", view.topic.name)
        assertEquals(listOf("Alltag"), view.path.map { it.name })
        assertEquals(listOf("Küche"), view.children.map { it.name })
        assertEquals(listOf("Besen", "Gießkanne"), view.words.map { it.lemma })
        assertEquals(listOf("Übung"), view.documents.map { it.title })
        assertEquals(listOf("Bild"), view.materials.map { it.name })
        assertEquals(3, view.lessons.size)

        val covered = view.coveredBy.single()
        assertEquals(STUDENT.toString(), covered.studentId)
        assertEquals(listOf(CoverageSource.LESSON, CoverageSource.VOCAB), covered.via)
        assertEquals(added.toInstant(), covered.lastAt.toInstant())

        val a2 = client.get("/api/v1/library/topics/$haushalt?level=A2") { bearerAuth(token) }.body<TopicLibrary>()
        assertEquals(listOf("Gießkanne"), a2.words.map { it.lemma })
        assertEquals(emptyList(), a2.materials)
        assertEquals(1, a2.coveredBy.size)
    }

    @Test
    fun `grammar checklist groups by level in position order with counts`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val dativ = LibraryFixtures.grammar("Dativ", LanguageLevel.A2, position = 1)
        val perfekt = LibraryFixtures.grammar("Perfekt", LanguageLevel.A2, position = 0)
        LibraryFixtures.grammar("Konjunktiv II", LanguageLevel.B1)
        LibraryFixtures.grammar("Aussprache")
        LibraryFixtures.document("Perfekt-Übung", grammar = listOf(perfekt))
        LibraryFixtures.material("Perfekt-Tabelle", grammar = listOf(perfekt))
        val lesson = seedLesson(students = listOf(STUDENT, STUDENT_2))
        LibraryFixtures.tagLesson(lesson, grammar = listOf(perfekt))

        val groups = client.get("/api/v1/library/grammar") { bearerAuth(token) }.body<List<GrammarLevelGroup>>()
        assertEquals(listOf(LanguageLevel.A2, LanguageLevel.B1, null), groups.map { it.level })
        val a2 = groups.first().topics
        assertEquals(listOf("Perfekt", "Dativ"), a2.map { it.grammarTopic.name })
        assertEquals(listOf(1L, 1L, 1L, 2L), a2.first().let { listOf(it.documents, it.materials, it.lessons, it.coveredStudents) })
        assertEquals(0L, a2.single { it.grammarTopic.id == dativ.toString() }.coveredStudents)

        val onlyB1 = client.get("/api/v1/library/grammar?level=B1") { bearerAuth(token) }.body<List<GrammarLevelGroup>>()
        assertEquals(listOf("Konjunktiv II"), onlyB1.single().topics.map { it.grammarTopic.name })

        val detail = client.get("/api/v1/library/grammar/$perfekt") { bearerAuth(token) }.body<GrammarTopicLibrary>()
        assertEquals(listOf("Perfekt-Übung"), detail.documents.map { it.title })
        assertEquals(listOf("Perfekt-Tabelle"), detail.materials.map { it.name })
        assertEquals(listOf(lesson.toString()), detail.lessons.map { it.id })
        assertEquals(setOf(STUDENT.toString(), STUDENT_2.toString()), detail.coveredBy.map { it.studentId }.toSet())
        detail.coveredBy.forEach { assertEquals(listOf(CoverageSource.LESSON), it.via) }
    }

    @Test
    fun `library views are for the owning teacher only`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val topic = LibraryFixtures.topic("Alltag")
        val grammar = LibraryFixtures.grammar("Perfekt")

        listOf(getStudentToken(client), getAdminToken(client)).forEach { other ->
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/library/summary") { bearerAuth(other) }.status)
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/library/topics/$topic") { bearerAuth(other) }.status)
        }
        LibraryFixtures.otherTeacher()
        val otherToken = getToken(client, "teacher2@test.com")
        val notFound = client.get("/api/v1/library/topics/$topic") { bearerAuth(otherToken) }
        assertEquals(HttpStatusCode.NotFound, notFound.status)
        assertEquals("TOPIC_NOT_FOUND", notFound.body<ProblemDetail>().code)
        assertEquals("GRAMMAR_TOPIC_NOT_FOUND", client.get("/api/v1/library/grammar/$grammar") { bearerAuth(otherToken) }.body<ProblemDetail>().code)
        assertEquals(emptyList(), client.get("/api/v1/library/summary") { bearerAuth(otherToken) }.body<LibrarySummary>().topics)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/library/grammar/$grammar") { bearerAuth(token) }.status)
    }
}
