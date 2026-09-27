package com.gvart.parleyroom.topic.service

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.service.AuthorizationHelper
import com.gvart.parleyroom.common.service.findByIdOrThrow
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ConflictException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.topic.data.TopicTable
import com.gvart.parleyroom.topic.transfer.CreateTopicRequest
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

    fun deleteTopic(topicId: UUID, principal: UserPrincipal) = transaction {
        requireOwned(findTopic(topicId), principal)
        val hasChildren = !TopicTable.selectAll().where { TopicTable.parentId eq topicId }.empty()
        if (hasChildren)
            throw ConflictException("Topic has sub-topics; delete or move them first", code = "TOPIC_HAS_CHILDREN")
        TopicTable.deleteWhere { id eq topicId }
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
