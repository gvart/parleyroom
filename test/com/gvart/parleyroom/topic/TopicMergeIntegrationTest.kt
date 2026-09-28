package com.gvart.parleyroom.topic

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.ai.seedLesson
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.common.transfer.TagUsage
import com.gvart.parleyroom.document.data.DocumentAudience
import com.gvart.parleyroom.document.data.DocumentTable
import com.gvart.parleyroom.document.data.DocumentTopicTable
import com.gvart.parleyroom.document.transfer.DocumentResponse
import com.gvart.parleyroom.document.transfer.UpdateDocumentRequest
import com.gvart.parleyroom.lesson.data.LessonTopicTable
import com.gvart.parleyroom.library.LibraryFixtures
import com.gvart.parleyroom.material.data.MaterialTable
import com.gvart.parleyroom.material.data.MaterialTopicTable
import com.gvart.parleyroom.topic.data.TopicTable
import com.gvart.parleyroom.topic.transfer.MergePreview
import com.gvart.parleyroom.topic.transfer.MergeRequest
import com.gvart.parleyroom.topic.transfer.TopicResponse
import com.gvart.parleyroom.vocabulary.data.VocabEntryTable
import com.gvart.parleyroom.vocabulary.data.VocabEntryTopicTable
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TopicMergeIntegrationTest : IntegrationTest() {

    private suspend fun HttpClient.merge(token: String, source: UUID, target: UUID, dryRun: Boolean = false): HttpResponse =
        post("/api/v1/topics/$source/merge" + if (dryRun) "?dryRun=true" else "") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(MergeRequest(target.toString()))
        }

    private fun exists(id: UUID) = transaction { !TopicTable.selectAll().where { TopicTable.id eq id }.empty() }

    private fun parentOf(id: UUID) = transaction { TopicTable.selectAll().where { TopicTable.id eq id }.single()[TopicTable.parentId]?.value }

    /** Tag ids of [item] in a join table. */
    private fun tags(table: Table, itemColumn: Column<EntityID<UUID>>, tagColumn: Column<EntityID<UUID>>, item: UUID): List<UUID> = transaction {
        table.selectAll().where { itemColumn eq item }.map { it[tagColumn].value }
    }

    @Test
    fun `deleting a topic that still tags content needs force and never deletes the content`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val topic = LibraryFixtures.topic("Haushalt")
        val entry = LibraryFixtures.entry("Gießkanne", topics = listOf(topic))
        val document = LibraryFixtures.document("Übung", topics = listOf(topic))
        val material = LibraryFixtures.material("Bild", topics = listOf(topic))
        LibraryFixtures.tagLesson(seedLesson(), topics = listOf(topic))

        val blocked = client.delete("/api/v1/topics/$topic") { bearerAuth(token) }
        assertEquals(HttpStatusCode.Conflict, blocked.status)
        val problem = blocked.body<ProblemDetail>()
        assertEquals("TOPIC_HAS_CONTENT", problem.code)
        assertEquals(TagUsage(words = 1, documents = 1, materials = 1, lessons = 1), problem.usage)
        assertTrue(exists(topic))

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/topics/$topic?force=true") { bearerAuth(token) }.status)
        assertFalse(exists(topic))
        transaction {
            assertEquals(1, VocabEntryTable.selectAll().where { VocabEntryTable.id eq entry }.count())
            assertEquals(1, DocumentTable.selectAll().where { DocumentTable.id eq document }.count())
            assertEquals(1, MaterialTable.selectAll().where { MaterialTable.id eq material }.count())
        }
        assertEquals(emptyList(), tags(VocabEntryTopicTable, VocabEntryTopicTable.vocabEntryId, VocabEntryTopicTable.topicId, entry))
    }

    @Test
    fun `an unused topic is deleted without force, children still block with force`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val root = LibraryFixtures.topic("Alltag")
        LibraryFixtures.topic("Haushalt", parentId = root)
        val unused = LibraryFixtures.topic("Reisen")

        val blocked = client.delete("/api/v1/topics/$root?force=true") { bearerAuth(token) }
        assertEquals("TOPIC_HAS_CHILDREN", blocked.body<ProblemDetail>().code)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/topics/$unused") { bearerAuth(token) }.status)
    }

    @Test
    fun `merge re-points every tag, dedupes join rows and unions levels`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val source = LibraryFixtures.topic("Wohnung", levels = listOf(LanguageLevel.A2))
        val target = LibraryFixtures.topic("Wohnen", levels = listOf(LanguageLevel.B1))
        val entry = LibraryFixtures.entry("Miete", topics = listOf(source))
        val both = LibraryFixtures.document("Beides", topics = listOf(source, target))
        val material = LibraryFixtures.material("Grundriss", topics = listOf(source))
        val lesson = seedLesson()
        LibraryFixtures.tagLesson(lesson, topics = listOf(source, target))

        val response = client.merge(token, source, target)
        assertEquals(HttpStatusCode.OK, response.status)
        val merged = response.body<TopicResponse>()
        assertEquals(target.toString(), merged.id)
        assertEquals("Wohnen", merged.name)
        assertEquals(listOf(LanguageLevel.A2, LanguageLevel.B1), merged.levels)
        assertFalse(exists(source))

        assertEquals(listOf(target), tags(VocabEntryTopicTable, VocabEntryTopicTable.vocabEntryId, VocabEntryTopicTable.topicId, entry))
        assertEquals(listOf(target), tags(DocumentTopicTable, DocumentTopicTable.documentId, DocumentTopicTable.topicId, both))
        assertEquals(listOf(target), tags(MaterialTopicTable, MaterialTopicTable.materialId, MaterialTopicTable.topicId, material))
        assertEquals(listOf(target), tags(LessonTopicTable, LessonTopicTable.lessonId, LessonTopicTable.topicId, lesson))
    }

    @Test
    fun `merge moves children and merges same-name children recursively`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val source = LibraryFixtures.topic("Alltag")
        val sourceHaushalt = LibraryFixtures.topic("Haushalt", parentId = source)
        val sourceGarten = LibraryFixtures.topic("Garten", parentId = sourceHaushalt)
        val kueche = LibraryFixtures.topic("Küche", parentId = source)
        val target = LibraryFixtures.topic("Wohnen")
        val targetHaushalt = LibraryFixtures.topic("haushalt", parentId = target)
        val entry = LibraryFixtures.entry("Gießkanne", topics = listOf(sourceHaushalt))

        assertEquals(HttpStatusCode.OK, client.merge(token, source, target).status)

        assertEquals(target, parentOf(kueche))
        assertFalse(exists(sourceHaushalt))
        assertEquals(targetHaushalt, parentOf(sourceGarten))
        assertEquals(listOf(targetHaushalt), tags(VocabEntryTopicTable, VocabEntryTopicTable.vocabEntryId, VocabEntryTopicTable.topicId, entry))
    }

    @Test
    fun `merging into the parent works when a child has the parent's name`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val parent = LibraryFixtures.topic("Alltag")
        val source = LibraryFixtures.topic("Haushalt", parentId = parent)
        val grandChild = LibraryFixtures.topic("Haushalt", parentId = source)

        assertEquals(HttpStatusCode.OK, client.merge(token, source, parent).status)
        assertEquals(parent, parentOf(grandChild))
        assertFalse(exists(source))
    }

    @Test
    fun `dry run previews the counts and changes nothing`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val source = LibraryFixtures.topic("Alltag")
        val target = LibraryFixtures.topic("Wohnen")
        LibraryFixtures.topic("Haushalt", parentId = source)
        LibraryFixtures.topic("Küche", parentId = source)
        LibraryFixtures.topic("haushalt", parentId = target)
        LibraryFixtures.entry("Miete", topics = listOf(source))
        LibraryFixtures.entry("Wand", topics = listOf(source, target))
        LibraryFixtures.document("Übung", topics = listOf(source))

        val response = client.merge(token, source, target, dryRun = true)
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(MergePreview(words = 1, documents = 1, materials = 0, lessons = 0, children = 2, childClashes = 1), response.body<MergePreview>())
        assertTrue(exists(source))
        assertEquals(2, transaction { VocabEntryTopicTable.selectAll().where { VocabEntryTopicTable.topicId eq source }.count() })
    }

    @Test
    fun `merge into itself or a descendant is invalid, other teachers' topics are not found`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val root = LibraryFixtures.topic("Alltag")
        val child = LibraryFixtures.topic("Haushalt", parentId = root)

        assertEquals("TOPIC_MERGE_INVALID", client.merge(token, root, root).body<ProblemDetail>().code)
        val intoChild = client.merge(token, root, child)
        assertEquals(HttpStatusCode.BadRequest, intoChild.status)
        assertEquals("TOPIC_MERGE_INVALID", intoChild.body<ProblemDetail>().code)

        val foreign = LibraryFixtures.topic("Fremd", teacherId = LibraryFixtures.otherTeacher())
        val notFound = client.merge(token, root, foreign)
        assertEquals(HttpStatusCode.NotFound, notFound.status)
        assertEquals("TOPIC_NOT_FOUND", notFound.body<ProblemDetail>().code)
        val otherToken = getToken(client, "teacher2@test.com")
        assertEquals(HttpStatusCode.NotFound, client.merge(otherToken, root, foreign).status)
        assertTrue(exists(root))
    }

    @Test
    fun `merge rewrites vocab_table topicId and bumps the revision so a stale editor reloads`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val source = LibraryFixtures.topic("Wohnung")
        val target = LibraryFixtures.topic("Wohnen")
        val blocks = Json.parseToJsonElement(
            """[{"id":"${UUID.randomUUID()}","type":"heading","interactive":false,"text":"Hallo","level":1},
               {"id":"${UUID.randomUUID()}","type":"vocab_table","interactive":false,"topicId":"$source","rows":[]}]""",
        ) as JsonArray
        val documentId = LibraryFixtures.document("Wortschatz", blocks = blocks)
        val before = client.get("/api/v1/documents/$documentId") { bearerAuth(token) }.body<DocumentResponse>()

        assertEquals(HttpStatusCode.OK, client.merge(token, source, target).status)

        val after = client.get("/api/v1/documents/$documentId") { bearerAuth(token) }.body<DocumentResponse>()
        assertEquals(before.revision + 1, after.revision)
        assertEquals("heading", after.blocks[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(target.toString(), after.blocks[1].jsonObject["topicId"]!!.jsonPrimitive.content)

        val stale = client.put("/api/v1/documents/$documentId") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(UpdateDocumentRequest(title = "Wortschatz", audience = DocumentAudience.LIBRARY, blocks = before.blocks, revision = before.revision))
        }
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assertEquals("DOCUMENT_CONFLICT", stale.body<ProblemDetail>().code)
    }
}
