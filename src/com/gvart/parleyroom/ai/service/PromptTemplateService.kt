package com.gvart.parleyroom.ai.service

import com.gvart.parleyroom.ai.data.PromptTemplateLessonType
import com.gvart.parleyroom.ai.data.PromptTemplateTable
import com.gvart.parleyroom.ai.transfer.PromptTemplateInput
import com.gvart.parleyroom.ai.transfer.PromptTemplateResponse
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.transfer.exception.ConflictException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.topic.service.LibraryAccess
import com.gvart.parleyroom.user.security.UserPrincipal
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime
import java.util.UUID

/** Anna's saved prompts. Per teacher; other teachers' templates are 404. */
class PromptTemplateService {

    fun list(principal: UserPrincipal, lessonType: PromptTemplateLessonType?, level: LanguageLevel?): List<PromptTemplateResponse> = transaction {
        LibraryAccess.requireTeacher(principal)
        val query = PromptTemplateTable.selectAll().where { PromptTemplateTable.teacherId eq principal.id }
        lessonType?.let { query.andWhere { (PromptTemplateTable.lessonType eq it) or PromptTemplateTable.lessonType.isNull() } }
        level?.let { query.andWhere { (PromptTemplateTable.level eq it) or PromptTemplateTable.level.isNull() } }
        query.orderBy(PromptTemplateTable.name.lowerCase()).map(::toResponse)
    }

    fun get(id: UUID, principal: UserPrincipal): PromptTemplateResponse = transaction { toResponse(requireOwned(id, principal)) }

    fun create(input: PromptTemplateInput, principal: UserPrincipal): PromptTemplateResponse = transaction {
        LibraryAccess.requireTeacher(principal)
        requireUniqueName(principal.id, input.name, excludeId = null)
        val now = OffsetDateTime.now()
        val id = PromptTemplateTable.insertAndGetId {
            it[teacherId] = principal.id
            it[name] = input.name.trim()
            it[text] = input.text
            it[level] = input.level
            it[lessonType] = input.lessonType
            it[createdAt] = now
            it[updatedAt] = now
        }.value
        toResponse(requireOwned(id, principal))
    }

    fun update(id: UUID, input: PromptTemplateInput, principal: UserPrincipal): PromptTemplateResponse = transaction {
        requireOwned(id, principal)
        requireUniqueName(principal.id, input.name, excludeId = id)
        PromptTemplateTable.update({ PromptTemplateTable.id eq id }) {
            it[name] = input.name.trim()
            it[text] = input.text
            it[level] = input.level
            it[lessonType] = input.lessonType
            it[updatedAt] = OffsetDateTime.now()
        }
        toResponse(requireOwned(id, principal))
    }

    fun delete(id: UUID, principal: UserPrincipal) = transaction {
        requireOwned(id, principal)
        PromptTemplateTable.deleteWhere { PromptTemplateTable.id eq id }
    }

    private fun requireOwned(id: UUID, principal: UserPrincipal): ResultRow {
        LibraryAccess.requireTeacher(principal)
        return PromptTemplateTable.selectAll()
            .where { (PromptTemplateTable.id eq id) and (PromptTemplateTable.teacherId eq principal.id) }
            .singleOrNull() ?: throw NotFoundException("Prompt template not found", code = "PROMPT_TEMPLATE_NOT_FOUND")
    }

    private fun requireUniqueName(teacherId: UUID, name: String, excludeId: UUID?) {
        val query = PromptTemplateTable.selectAll().where {
            (PromptTemplateTable.teacherId eq teacherId) and (PromptTemplateTable.name.lowerCase() eq name.trim().lowercase())
        }
        excludeId?.let { query.andWhere { PromptTemplateTable.id neq it } }
        if (!query.empty())
            throw ConflictException("A prompt template named '${name.trim()}' already exists", code = "PROMPT_TEMPLATE_DUPLICATE")
    }

    private fun toResponse(row: ResultRow) = PromptTemplateResponse(
        id = row[PromptTemplateTable.id].value.toString(),
        teacherId = row[PromptTemplateTable.teacherId].value.toString(),
        name = row[PromptTemplateTable.name],
        text = row[PromptTemplateTable.text],
        level = row[PromptTemplateTable.level],
        lessonType = row[PromptTemplateTable.lessonType],
        createdAt = row[PromptTemplateTable.createdAt],
        updatedAt = row[PromptTemplateTable.updatedAt],
    )
}
