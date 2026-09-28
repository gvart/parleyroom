package com.gvart.parleyroom.topic.service

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.service.AuthorizationHelper
import com.gvart.parleyroom.common.service.findByIdOrThrow
import com.gvart.parleyroom.common.transfer.exception.ConflictException
import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.topic.data.GrammarTopicTable
import com.gvart.parleyroom.topic.transfer.GrammarTopicRequest
import com.gvart.parleyroom.topic.transfer.GrammarTopicResponse
import com.gvart.parleyroom.user.security.UserPrincipal
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
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

class GrammarTopicService {

    fun listGrammarTopics(principal: UserPrincipal, level: LanguageLevel?): List<GrammarTopicResponse> = transaction {
        val query = GrammarTopicTable.selectAll()
        LibraryAccess.readableTeacherIds(principal)?.let { ids -> query.andWhere { GrammarTopicTable.teacherId inList ids } }
        if (level != null) query.andWhere { GrammarTopicTable.level eq level }
        query.orderBy(GrammarTopicTable.level to SortOrder.ASC_NULLS_LAST, GrammarTopicTable.name to SortOrder.ASC)
            .map(::toResponse)
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
            it[createdAt] = now
            it[updatedAt] = now
        }
        toResponse(find(id.value))
    }

    fun updateGrammarTopic(id: UUID, request: GrammarTopicRequest, principal: UserPrincipal): GrammarTopicResponse = transaction {
        val row = find(id)
        requireOwned(row, principal)
        ensureNameUnique(row[GrammarTopicTable.teacherId].value, request.name, excludeId = id)
        GrammarTopicTable.update({ GrammarTopicTable.id eq id }) {
            it[name] = request.name.trim()
            it[level] = request.level
            it[category] = request.category
            it[explanation] = request.explanation
            it[examples] = request.examples
        }
        toResponse(find(id))
    }

    fun deleteGrammarTopic(id: UUID, principal: UserPrincipal) = transaction {
        requireOwned(find(id), principal)
        GrammarTopicTable.deleteWhere { GrammarTopicTable.id eq id }
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

    private fun toResponse(row: ResultRow) = GrammarTopicResponse(
        id = row[GrammarTopicTable.id].value.toString(),
        teacherId = row[GrammarTopicTable.teacherId].value.toString(),
        name = row[GrammarTopicTable.name],
        level = row[GrammarTopicTable.level],
        category = row[GrammarTopicTable.category],
        explanation = row[GrammarTopicTable.explanation],
        examples = row[GrammarTopicTable.examples],
        createdAt = row[GrammarTopicTable.createdAt],
    )
}
