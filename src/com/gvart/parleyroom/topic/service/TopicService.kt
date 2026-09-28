package com.gvart.parleyroom.topic.service

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.data.Sql
import com.gvart.parleyroom.common.service.AuthorizationHelper
import com.gvart.parleyroom.common.service.findByIdOrThrow
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ConflictException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.topic.data.TopicTable
import com.gvart.parleyroom.topic.transfer.CreateTopicRequest
import com.gvart.parleyroom.topic.transfer.MergePreview
import com.gvart.parleyroom.topic.transfer.MergeRequest
import com.gvart.parleyroom.topic.transfer.TopicResponse
import com.gvart.parleyroom.topic.transfer.UpdateTopicRequest
import com.gvart.parleyroom.user.security.UserPrincipal
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
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

class TopicService {

    /** Flat list ordered by name; the client builds the tree from parentId. */
    fun listTopics(principal: UserPrincipal): List<TopicResponse> = transaction {
        val query = TopicTable.selectAll()
        LibraryAccess.readableTeacherIds(principal)?.let { ids -> query.andWhere { TopicTable.teacherId inList ids } }
        query.orderBy(TopicTable.name).map(::toResponse)
    }

    fun createTopic(request: CreateTopicRequest, principal: UserPrincipal): TopicResponse = transaction {
        LibraryAccess.requireTeacher(principal)
        val parentId = request.parentId?.let(UUID::fromString)
        if (parentId != null) requireOwned(findTopic(parentId), principal)

        ensureNameUnique(principal.id, parentId, request.name, excludeId = null)

        val now = OffsetDateTime.now()
        val id = TopicTable.insertAndGetId {
            it[teacherId] = principal.id
            it[TopicTable.parentId] = parentId
            it[name] = request.name.trim()
            it[levels] = request.levels.distinct().map(LanguageLevel::name)
            it[createdAt] = now
            it[updatedAt] = now
        }
        toResponse(findTopic(id.value))
    }

    fun updateTopic(topicId: UUID, request: UpdateTopicRequest, principal: UserPrincipal): TopicResponse = transaction {
        val row = findTopic(topicId)
        requireOwned(row, principal)
        val teacherId = row[TopicTable.teacherId].value

        val newParent: UUID? = when {
            request.moveToRoot -> null
            request.parentId != null -> UUID.fromString(request.parentId).also { parentId ->
                val parent = findTopic(parentId)
                if (parent[TopicTable.teacherId].value != teacherId)
                    throw NotFoundException("Topic not found", code = "TOPIC_NOT_FOUND")
                ensureNoCycle(topicId, parentId)
            }
            else -> row[TopicTable.parentId]?.value
        }
        val newName = request.name?.trim() ?: row[TopicTable.name]
        ensureNameUnique(teacherId, newParent, newName, excludeId = topicId)

        TopicTable.update({ TopicTable.id eq topicId }) {
            it[parentId] = newParent
            it[name] = newName
            if (request.levels != null) it[levels] = request.levels.distinct().map(LanguageLevel::name)
        }
        toResponse(findTopic(topicId))
    }

    /** Without [force], a topic that still tags anything is kept (409 with its usage); tags are removed, never content. */
    fun deleteTopic(topicId: UUID, principal: UserPrincipal, force: Boolean) = transaction {
        requireOwned(findTopic(topicId), principal)
        val hasChildren = !TopicTable.selectAll().where { TopicTable.parentId eq topicId }.empty()
        if (hasChildren)
            throw ConflictException("Topic has sub-topics; delete or move them first", code = "TOPIC_HAS_CHILDREN")
        val usage = TagLinks.TOPICS.usage(topicId)
        if (usage.total > 0 && !force)
            throw ConflictException("Topic is still used; delete with force=true to remove the tags", code = "TOPIC_HAS_CONTENT", usage = usage)
        TopicTable.deleteWhere { id eq topicId }
    }

    /** What [merge] would move, without changing anything. */
    fun previewMerge(sourceId: UUID, request: MergeRequest, principal: UserPrincipal): MergePreview = transaction {
        val targetId = requireMergeable(sourceId, request, principal)
        val moved = TagLinks.TOPICS.movable(sourceId, targetId)
        val children = children(sourceId)
        val targetChildren = children(targetId).map { it.second.lowercase() }.toSet()
        MergePreview(
            words = moved.words,
            documents = moved.documents,
            materials = moved.materials,
            lessons = moved.lessons,
            children = children.size.toLong(),
            childClashes = children.count { it.second.lowercase() in targetChildren }.toLong(),
        )
    }

    /** Merges [sourceId] into the target (see API.md "Merge"); returns the target. */
    fun merge(sourceId: UUID, request: MergeRequest, principal: UserPrincipal): TopicResponse = transaction {
        val targetId = requireMergeable(sourceId, request, principal)
        mergeInto(principal.id, sourceId, targetId)
        toResponse(findTopic(targetId))
    }

    private fun requireMergeable(sourceId: UUID, request: MergeRequest, principal: UserPrincipal): UUID {
        LibraryAccess.requireTeacher(principal)
        val targetId = parseUuid(request.targetId, "TOPIC_NOT_FOUND")
        listOf(sourceId, targetId).forEach { id ->
            val row = TopicTable.selectAll().where { TopicTable.id eq id }.singleOrNull()
            if (row == null || row[TopicTable.teacherId].value != principal.id)
                throw NotFoundException("Topic not found", code = "TOPIC_NOT_FOUND")
        }
        if (isInSubtree(targetId, sourceId))
            throw BadRequestException("A topic cannot be merged into itself or one of its sub-topics", code = "TOPIC_MERGE_INVALID")
        return targetId
    }

    private fun mergeInto(teacherId: UUID, sourceId: UUID, targetId: UUID) {
        val source = findTopic(sourceId)
        // Free the source's name first: a child of it may carry the same name and move next to it.
        TopicTable.update({ TopicTable.id eq sourceId }) { it[name] = sourceId.toString() }

        val targetChildren = children(targetId).filter { it.first != sourceId }
        children(sourceId).forEach { (childId, childName) ->
            val clash = targetChildren.firstOrNull { it.second.equals(childName, ignoreCase = true) }
            if (clash != null) mergeInto(teacherId, childId, clash.first)
            else TopicTable.update({ TopicTable.id eq childId }) { it[parentId] = targetId }
        }

        TagLinks.TOPICS.repoint(sourceId, targetId)
        rewriteVocabTableTopic(teacherId, sourceId, targetId)
        val target = findTopic(targetId)
        TopicTable.update({ TopicTable.id eq targetId }) {
            it[levels] = (target[levels] + source[levels]).distinct().sortedBy(LanguageLevel::valueOf)
        }
        TopicTable.deleteWhere { id eq sourceId }
    }

    /**
     * `vocab_table.topicId` inside document blocks is a soft reference: re-point it and bump the
     * revision so an open editor reloads instead of autosaving the old id back.
     */
    private fun rewriteVocabTableTopic(teacherId: UUID, sourceId: UUID, targetId: UUID) = Sql.update(
        """
        UPDATE documents d
        SET blocks = (SELECT jsonb_agg(CASE WHEN e ->> 'type' = 'vocab_table' AND e ->> 'topicId' = ?::text
                                            THEN jsonb_set(e, '{topicId}', to_jsonb(?::text)) ELSE e END ORDER BY i)
                      FROM jsonb_array_elements(d.blocks) WITH ORDINALITY AS x(e, i)),
            revision = d.revision + 1,
            updated_at = now()
        WHERE d.owner_id = ?
          AND d.blocks @> jsonb_build_array(jsonb_build_object('type', 'vocab_table', 'topicId', ?::text))
        """.trimIndent(),
        sourceId, targetId, teacherId, sourceId,
    )

    private fun children(parentId: UUID): List<Pair<UUID, String>> =
        TopicTable.select(TopicTable.id, TopicTable.name).where { TopicTable.parentId eq parentId }
            .map { it[TopicTable.id].value to it[TopicTable.name] }

    /** True if [topicId] is [rootId] or one of its descendants. */
    private fun isInSubtree(topicId: UUID, rootId: UUID): Boolean {
        var cursor: UUID? = topicId
        while (cursor != null) {
            if (cursor == rootId) return true
            cursor = TopicTable.selectAll().where { TopicTable.id eq cursor!! }.single()[TopicTable.parentId]?.value
        }
        return false
    }

    private fun findTopic(id: UUID): ResultRow = TopicTable.findByIdOrThrow(id, "Topic")

    private fun requireOwned(row: ResultRow, principal: UserPrincipal) =
        AuthorizationHelper.requireOwnerOrAdmin(row[TopicTable.teacherId].value, principal, "Not your topic")

    private fun ensureNoCycle(topicId: UUID, newParentId: UUID) {
        var cursor: UUID? = newParentId
        while (cursor != null) {
            if (cursor == topicId)
                throw BadRequestException("A topic cannot be moved under itself or its descendants", code = "TOPIC_CYCLE")
            cursor = TopicTable.selectAll().where { TopicTable.id eq cursor!! }.single()[TopicTable.parentId]?.value
        }
    }

    private fun ensureNameUnique(teacherId: UUID, parentId: UUID?, name: String, excludeId: UUID?) {
        val parentCond: Op<Boolean> = if (parentId == null) TopicTable.parentId.isNull() else TopicTable.parentId eq parentId
        val query = TopicTable.selectAll().where {
            (TopicTable.teacherId eq teacherId) and parentCond and
                    (TopicTable.name.lowerCase() eq name.trim().lowercase())
        }
        if (excludeId != null) query.andWhere { TopicTable.id neq excludeId }
        if (!query.empty())
            throw ConflictException("A topic named '${name.trim()}' already exists here", code = "TOPIC_DUPLICATE")
    }

    private fun toResponse(row: ResultRow) = TopicResponse(
        id = row[TopicTable.id].value.toString(),
        teacherId = row[TopicTable.teacherId].value.toString(),
        parentId = row[TopicTable.parentId]?.value?.toString(),
        name = row[TopicTable.name],
        levels = row[TopicTable.levels].map(LanguageLevel::valueOf),
        createdAt = row[TopicTable.createdAt],
    )
}
