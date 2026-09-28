package com.gvart.parleyroom.ai.service

import com.gvart.parleyroom.ai.data.GenerationJobKind
import com.gvart.parleyroom.ai.data.GenerationJobStatus
import com.gvart.parleyroom.ai.data.GenerationJobTable
import com.gvart.parleyroom.ai.transfer.LibrarySuggestions
import com.gvart.parleyroom.ai.transfer.SuggestedDocument
import com.gvart.parleyroom.ai.transfer.SuggestedMaterial
import com.gvart.parleyroom.ai.transfer.SuggestionKind
import com.gvart.parleyroom.ai.transfer.SuggestionSummary
import com.gvart.parleyroom.document.data.DocumentGrammarTopicTable
import com.gvart.parleyroom.document.data.DocumentTable
import com.gvart.parleyroom.document.data.DocumentTopicTable
import com.gvart.parleyroom.document.service.DocumentSupport
import com.gvart.parleyroom.lesson.data.LessonGrammarTopicTable
import com.gvart.parleyroom.lesson.data.LessonTopicTable
import com.gvart.parleyroom.material.data.MaterialGrammarTopicTable
import com.gvart.parleyroom.material.data.MaterialTable
import com.gvart.parleyroom.material.data.MaterialTopicTable
import com.gvart.parleyroom.material.service.MaterialService
import com.gvart.parleyroom.topic.data.TopicTable
import com.gvart.parleyroom.topic.service.LibraryAccess
import com.gvart.parleyroom.user.security.UserPrincipal
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

/** "You already have 3 B1 exercises on …": library documents and materials matching a lesson. No AI. */
class LibrarySuggestionService(
    private val context: LessonContextService,
    private val documentSupport: DocumentSupport,
    private val materialService: MaterialService,
) {

    fun suggestions(lessonId: UUID, principal: UserPrincipal): LibrarySuggestions = transaction {
        val lesson = context.requireLessonTeacher(lessonId, principal)
        val ctx = context.load(lesson)
        val teacherId = ctx.teacherId

        val topicIds = (LessonTopicTable.select(LessonTopicTable.topicId).where { LessonTopicTable.lessonId eq lessonId }
            .map { it[LessonTopicTable.topicId].value } + latestSuggestions(lessonId, grammar = false)).toSet()
        val grammarIds = (LessonGrammarTopicTable.select(LessonGrammarTopicTable.grammarTopicId)
            .where { LessonGrammarTopicTable.lessonId eq lessonId }
            .map { it[LessonGrammarTopicTable.grammarTopicId].value } + latestSuggestions(lessonId, grammar = true)).toSet()
        if (topicIds.isEmpty() && grammarIds.isEmpty()) return@transaction LibrarySuggestions(ctx.level, emptyList(), emptyList(), emptyList())

        // Documents: same level (or none), not made from this lesson, sharing at least one tag.
        val docTopics = tagMap(DocumentTopicTable.select(DocumentTopicTable.documentId, DocumentTopicTable.topicId)
            .where { DocumentTopicTable.topicId inList topicIds }.map { it[DocumentTopicTable.documentId].value to it[DocumentTopicTable.topicId].value })
        val docGrammar = tagMap(DocumentGrammarTopicTable.select(DocumentGrammarTopicTable.documentId, DocumentGrammarTopicTable.grammarTopicId)
            .where { DocumentGrammarTopicTable.grammarTopicId inList grammarIds }
            .map { it[DocumentGrammarTopicTable.documentId].value to it[DocumentGrammarTopicTable.grammarTopicId].value })
        val documentRows = (docTopics.keys + docGrammar.keys).takeIf { it.isNotEmpty() }?.let { ids ->
            val query = DocumentTable.selectAll().where {
                (DocumentTable.id inList ids) and (DocumentTable.ownerId eq teacherId) and
                        ((DocumentTable.createdFromLessonId neq lessonId) or DocumentTable.createdFromLessonId.isNull())
            }
            ctx.level?.let { level -> query.andWhere { (DocumentTable.level eq level) or DocumentTable.level.isNull() } }
            query.orderBy(DocumentTable.updatedAt, SortOrder.DESC).toList()
        }.orEmpty()
        val rankedDocuments = rank(documentRows, { it[DocumentTable.id].value }, docTopics, docGrammar)

        val matTopics = tagMap(MaterialTopicTable.select(MaterialTopicTable.materialId, MaterialTopicTable.topicId)
            .where { MaterialTopicTable.topicId inList topicIds }.map { it[MaterialTopicTable.materialId].value to it[MaterialTopicTable.topicId].value })
        val matGrammar = tagMap(MaterialGrammarTopicTable.select(MaterialGrammarTopicTable.materialId, MaterialGrammarTopicTable.grammarTopicId)
            .where { MaterialGrammarTopicTable.grammarTopicId inList grammarIds }
            .map { it[MaterialGrammarTopicTable.materialId].value to it[MaterialGrammarTopicTable.grammarTopicId].value })
        val materialRows = (matTopics.keys + matGrammar.keys).takeIf { it.isNotEmpty() }?.let { ids ->
            val query = MaterialTable.selectAll().where { (MaterialTable.id inList ids) and (MaterialTable.teacherId eq teacherId) }
            ctx.level?.let { level -> query.andWhere { (MaterialTable.level eq level) or MaterialTable.level.isNull() } }
            query.orderBy(MaterialTable.createdAt, SortOrder.DESC).toList()
        }.orEmpty()
        val rankedMaterials = rank(materialRows, { it[MaterialTable.id].value }, matTopics, matGrammar)

        val topicNames = TopicTable.selectAll().where { TopicTable.id inList topicIds }.associate { it[TopicTable.id].value to it[TopicTable.name] }
        val grammarRefs = LibraryAccess.grammarTopicRefs(grammarIds)
        val summary = topicIds.mapNotNull { id ->
            val docs = documentRows.count { id in docTopics[it[DocumentTable.id].value].orEmpty() }
            val mats = materialRows.count { id in matTopics[it[MaterialTable.id].value].orEmpty() }
            topicNames[id]?.takeIf { docs + mats > 0 }?.let { SuggestionSummary(SuggestionKind.TOPIC, id.toString(), it, ctx.level, docs, mats) }
        } + grammarIds.mapNotNull { id ->
            val docs = documentRows.count { id in docGrammar[it[DocumentTable.id].value].orEmpty() }
            val mats = materialRows.count { id in matGrammar[it[MaterialTable.id].value].orEmpty() }
            grammarRefs[id]?.takeIf { docs + mats > 0 }
                ?.let { SuggestionSummary(SuggestionKind.GRAMMAR, id.toString(), it.name, it.level ?: ctx.level, docs, mats) }
        }

        val summaries = documentSupport.toSummaries(rankedDocuments, principal).associateBy { it.id }
        val materials = materialService.toResponses(rankedMaterials).associateBy { it.id }
        LibrarySuggestions(
            level = ctx.level,
            summary = summary.sortedByDescending { it.documentCount + it.materialCount },
            documents = rankedDocuments.map { row ->
                val id = row[DocumentTable.id].value
                SuggestedDocument(summaries.getValue(id.toString()), docTopics[id].orEmpty().map(UUID::toString), docGrammar[id].orEmpty().map(UUID::toString))
            },
            materials = rankedMaterials.map { row ->
                val id = row[MaterialTable.id].value
                SuggestedMaterial(materials.getValue(id.toString()), matTopics[id].orEmpty().map(UUID::toString), matGrammar[id].orEmpty().map(UUID::toString))
            },
        )
    }

    /** Existing library ids the latest successful Nachbereitung job matched. */
    private fun latestSuggestions(lessonId: UUID, grammar: Boolean): List<UUID> {
        val row = GenerationJobTable.selectAll()
            .where {
                (GenerationJobTable.lessonId eq lessonId) and (GenerationJobTable.status eq GenerationJobStatus.SUCCEEDED) and
                        (GenerationJobTable.kind neq GenerationJobKind.FILL_TRANSLATIONS)
            }
            .orderBy(GenerationJobTable.createdAt, SortOrder.DESC)
            .limit(1)
            .singleOrNull() ?: return emptyList()
        val result = NachbereitungService.nachbereitungResult(row)
        val ids = if (grammar) result.grammarTopics.mapNotNull { it.existingId } else result.topics.mapNotNull { it.existingId }
        return ids.map(UUID::fromString)
    }

    private fun tagMap(pairs: List<Pair<UUID, UUID>>): Map<UUID, Set<UUID>> =
        pairs.groupBy({ it.first }) { it.second }.mapValues { it.value.toSet() }

    private fun rank(rows: List<ResultRow>, id: (ResultRow) -> UUID, topics: Map<UUID, Set<UUID>>, grammar: Map<UUID, Set<UUID>>): List<ResultRow> =
        rows.sortedByDescending { topics[id(it)].orEmpty().size + grammar[id(it)].orEmpty().size }.take(MAX_RESULTS)

    companion object {
        const val MAX_RESULTS = 10
    }
}
