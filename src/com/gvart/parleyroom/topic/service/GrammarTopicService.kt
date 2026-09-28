package com.gvart.parleyroom.topic.service

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.data.Sql
import com.gvart.parleyroom.common.service.AuthorizationHelper
import com.gvart.parleyroom.common.service.findByIdOrThrow
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ConflictException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.topic.data.GrammarTopicTable
import com.gvart.parleyroom.topic.transfer.GrammarTopicRequest
import com.gvart.parleyroom.topic.transfer.GrammarTopicResponse
import com.gvart.parleyroom.topic.transfer.MergePreview
import com.gvart.parleyroom.topic.transfer.MergeRequest
import com.gvart.parleyroom.topic.transfer.ReorderGrammarTopicsRequest
import com.gvart.parleyroom.user.security.UserPrincipal
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime
import java.util.UUID

class GrammarTopicService {

    fun listGrammarTopics(principal: UserPrincipal, level: LanguageLevel?): List<GrammarTopicResponse> = transaction {
        val query = GrammarTopicTable.selectAll()
        LibraryAccess.readableTeacherIds(principal)?.let { ids -> query.andWhere { GrammarTopicTable.teacherId inList ids } }
        if (level != null) query.andWhere { GrammarTopicTable.level eq level }
        query.orderBy(
            GrammarTopicTable.level to SortOrder.ASC_NULLS_LAST,
            GrammarTopicTable.position to SortOrder.ASC,
            GrammarTopicTable.name to SortOrder.ASC,
        ).map(::toResponse)
    }

    fun getGrammarTopic(id: UUID, principal: UserPrincipal): GrammarTopicResponse = transaction {
        val row = find(id)
        val readable = LibraryAccess.readableTeacherIds(principal)
        if (readable != null && row[GrammarTopicTable.teacherId].value !in readable)
            throw ForbiddenException("Not your grammar topic")
        toResponse(row)
    }

    fun createGrammarTopic(request: GrammarTopicRequest, principal: UserPrincipal): GrammarTopicResponse = transaction {
        LibraryAccess.requireTeacher(principal)
        ensureNameUnique(principal.id, request.name, excludeId = null)
        val now = OffsetDateTime.now()
        val id = GrammarTopicTable.insertAndGetId {
            it[teacherId] = principal.id
            it[name] = request.name.trim()
            it[level] = request.level
            it[category] = request.category
            it[explanation] = request.explanation
            it[examples] = request.examples
            it[position] = nextPosition(principal.id, request.level)
            it[createdAt] = now
            it[updatedAt] = now
        }
        toResponse(find(id.value))
    }

    fun updateGrammarTopic(id: UUID, request: GrammarTopicRequest, principal: UserPrincipal): GrammarTopicResponse = transaction {
        val row = find(id)
        requireOwned(row, principal)
        val teacherId = row[GrammarTopicTable.teacherId].value
        ensureNameUnique(teacherId, request.name, excludeId = id)
        val levelChanged = request.level != row[GrammarTopicTable.level]
        val newPosition = if (levelChanged) nextPosition(teacherId, request.level) else null
        GrammarTopicTable.update({ GrammarTopicTable.id eq id }) {
            if (newPosition != null) it[position] = newPosition
            it[name] = request.name.trim()
            it[level] = request.level
            it[category] = request.category
            it[explanation] = request.explanation
            it[examples] = request.examples
        }
        toResponse(find(id))
    }

    /** Without [force], a grammar topic that still tags anything is kept (409 with its usage); tags are removed, never content. */
    fun deleteGrammarTopic(id: UUID, principal: UserPrincipal, force: Boolean) = transaction {
        requireOwned(find(id), principal)
        val usage = TagLinks.GRAMMAR.usage(id)
        if (usage.total > 0 && !force)
            throw ConflictException("Grammar topic is still used; delete with force=true to remove the tags", code = "GRAMMAR_TOPIC_HAS_CONTENT", usage = usage)
        GrammarTopicTable.deleteWhere { GrammarTopicTable.id eq id }
    }

    /** `ids` must be exactly the teacher's grammar topics of the level; positions become the list index. */
    fun reorder(request: ReorderGrammarTopicsRequest, principal: UserPrincipal): List<GrammarTopicResponse> = transaction {
        LibraryAccess.requireTeacher(principal)
        val ids = try {
            request.ids.map(UUID::fromString)
        } catch (_: IllegalArgumentException) {
            throw BadRequestException("ids must be uuids", code = "GRAMMAR_ORDER_INVALID")
        }
        val current = GrammarTopicTable.select(GrammarTopicTable.id)
            .where { (GrammarTopicTable.teacherId eq principal.id) and levelIs(request.level) }
            .map { it[GrammarTopicTable.id].value }
            .toSet()
        if (ids.size != ids.toSet().size || ids.toSet() != current)
            throw BadRequestException("ids must list every grammar topic of the level exactly once", code = "GRAMMAR_ORDER_INVALID")
        ids.forEachIndexed { index, id ->
            GrammarTopicTable.update({ GrammarTopicTable.id eq id }) { it[position] = index }
        }
        GrammarTopicTable.selectAll()
            .where { (GrammarTopicTable.teacherId eq principal.id) and levelIs(request.level) }
            .orderBy(GrammarTopicTable.position)
            .map(::toResponse)
    }

    /** What [merge] would move, without changing anything. */
    fun previewMerge(sourceId: UUID, request: MergeRequest, principal: UserPrincipal): MergePreview = transaction {
        val (_, target) = mergePair(sourceId, request, principal)
        val moved = TagLinks.GRAMMAR.movable(sourceId, target[GrammarTopicTable.id].value)
        MergePreview(moved.words, moved.documents, moved.materials, moved.lessons, children = 0, childClashes = 0)
    }

    /**
     * Merges [sourceId] into the target: tags re-pointed (deduped), the target keeps its
     * name/level/position, empty category/explanation are taken from the source, examples unioned.
     */
    fun merge(sourceId: UUID, request: MergeRequest, principal: UserPrincipal): GrammarTopicResponse = transaction {
        val (source, target) = mergePair(sourceId, request, principal)
        val targetId = target[GrammarTopicTable.id].value
        TagLinks.GRAMMAR.repoint(sourceId, targetId)
        GrammarTopicTable.update({ GrammarTopicTable.id eq targetId }) {
            if (target[category].isNullOrBlank()) it[category] = source[category]
            if (target[explanation].isNullOrBlank()) it[explanation] = source[explanation]
            it[examples] = (target[examples] + source[examples]).distinct()
        }
        // Progress overrides move to the target; a student's existing override on the target wins.
        Sql.update(
            "UPDATE grammar_progress_overrides SET grammar_topic_id = ? WHERE grammar_topic_id = ? AND student_id NOT IN " +
                    "(SELECT student_id FROM grammar_progress_overrides WHERE grammar_topic_id = ?)",
            targetId, sourceId, targetId,
        )
        GrammarTopicTable.deleteWhere { GrammarTopicTable.id eq sourceId }
        toResponse(find(targetId))
    }

    private fun mergePair(sourceId: UUID, request: MergeRequest, principal: UserPrincipal): Pair<ResultRow, ResultRow> {
        LibraryAccess.requireTeacher(principal)
        val source = findOwn(sourceId, principal)
        val target = findOwn(parseUuid(request.targetId, "GRAMMAR_TOPIC_NOT_FOUND"), principal)
        if (sourceId == target[GrammarTopicTable.id].value)
            throw BadRequestException("A grammar topic cannot be merged into itself", code = "GRAMMAR_TOPIC_MERGE_INVALID")
        return source to target
    }

    private fun findOwn(id: UUID, principal: UserPrincipal): ResultRow {
        val row = GrammarTopicTable.selectAll().where { GrammarTopicTable.id eq id }.singleOrNull()
        if (row == null || row[GrammarTopicTable.teacherId].value != principal.id)
            throw NotFoundException("Grammar topic not found", code = "GRAMMAR_TOPIC_NOT_FOUND")
        return row
    }

    private fun find(id: UUID): ResultRow = GrammarTopicTable.findByIdOrThrow(id, "Grammar topic")

    private fun requireOwned(row: ResultRow, principal: UserPrincipal) =
        AuthorizationHelper.requireOwnerOrAdmin(row[GrammarTopicTable.teacherId].value, principal, "Not your grammar topic")

    private fun ensureNameUnique(teacherId: UUID, name: String, excludeId: UUID?) {
        val query = GrammarTopicTable.selectAll().where {
            (GrammarTopicTable.teacherId eq teacherId) and
                    (GrammarTopicTable.name.lowerCase() eq name.trim().lowercase())
        }
        if (excludeId != null) query.andWhere { GrammarTopicTable.id neq excludeId }
        if (!query.empty())
            throw ConflictException("A grammar topic named '${name.trim()}' already exists", code = "GRAMMAR_TOPIC_DUPLICATE")
    }

    fun toResponse(row: ResultRow) = GrammarTopicResponse(
        id = row[GrammarTopicTable.id].value.toString(),
        teacherId = row[GrammarTopicTable.teacherId].value.toString(),
        name = row[GrammarTopicTable.name],
        level = row[GrammarTopicTable.level],
        category = row[GrammarTopicTable.category],
        explanation = row[GrammarTopicTable.explanation],
        examples = row[GrammarTopicTable.examples],
        position = row[GrammarTopicTable.position],
        createdAt = row[GrammarTopicTable.createdAt],
    )

    companion object {
        private fun levelIs(level: LanguageLevel?): Op<Boolean> =
            if (level == null) GrammarTopicTable.level.isNull() else GrammarTopicTable.level eq level

        /** New grammar topics go to the end of their level's checklist. Must run in a transaction. */
        fun nextPosition(teacherId: UUID, level: LanguageLevel?): Int {
            val max = GrammarTopicTable.position.max()
            return GrammarTopicTable.select(max)
                .where { (GrammarTopicTable.teacherId eq teacherId) and levelIs(level) }
                .singleOrNull()?.get(max)?.plus(1) ?: 0
        }
    }
}
