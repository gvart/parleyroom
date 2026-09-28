package com.gvart.parleyroom.topic.service

import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.topic.data.GrammarTopicTable
import com.gvart.parleyroom.topic.data.TopicTable
import com.gvart.parleyroom.topic.transfer.GrammarTopicRef
import com.gvart.parleyroom.topic.transfer.TopicRef
import com.gvart.parleyroom.user.data.TeacherStudentTable
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.security.UserPrincipal
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

/**
 * Shared checks for the per-teacher library (topics, grammar topics, vocab entries, groups).
 * Must be called inside a transaction.
 */
object LibraryAccess {

    /** Library writes are teacher-only: each teacher owns their own library. */
    fun requireTeacher(principal: UserPrincipal) {
        if (principal.role != UserRole.TEACHER)
            throw ForbiddenException("Only teachers can manage their library")
    }

    /** Teachers read their own library; admins read all; students read their teachers' libraries. */
    fun readableTeacherIds(principal: UserPrincipal): List<UUID>? = when (principal.role) {
        UserRole.ADMIN -> null
        UserRole.TEACHER -> listOf(principal.id)
        UserRole.STUDENT -> TeacherStudentTable.selectAll()
            .where { TeacherStudentTable.studentId eq principal.id }
            .map { it[TeacherStudentTable.teacherId].value }
    }

    /** Validates that every id is a topic owned by [teacherId]; returns the distinct ids. */
    fun requireTopics(teacherId: UUID, ids: Collection<String>): List<UUID> {
        val uuids = ids.map(UUID::fromString).distinct()
        if (uuids.isEmpty()) return uuids
        val found = TopicTable.selectAll()
            .where { (TopicTable.id inList uuids) and (TopicTable.teacherId eq teacherId) }
            .count()
        if (found != uuids.size.toLong())
            throw NotFoundException("One or more topics not found", code = "TOPIC_NOT_FOUND")
        return uuids
    }

    fun requireGrammarTopics(teacherId: UUID, ids: Collection<String>): List<UUID> {
        val uuids = ids.map(UUID::fromString).distinct()
        if (uuids.isEmpty()) return uuids
        val found = GrammarTopicTable.selectAll()
            .where { (GrammarTopicTable.id inList uuids) and (GrammarTopicTable.teacherId eq teacherId) }
            .count()
        if (found != uuids.size.toLong())
            throw NotFoundException("One or more grammar topics not found", code = "GRAMMAR_TOPIC_NOT_FOUND")
        return uuids
    }

    fun topicRefs(ids: Collection<UUID>): Map<UUID, TopicRef> {
        if (ids.isEmpty()) return emptyMap()
        return TopicTable.selectAll()
            .where { TopicTable.id inList ids.distinct() }
            .associate { it[TopicTable.id].value to TopicRef(it[TopicTable.id].value.toString(), it[TopicTable.name]) }
    }

    fun grammarTopicRefs(ids: Collection<UUID>): Map<UUID, GrammarTopicRef> {
        if (ids.isEmpty()) return emptyMap()
        return GrammarTopicTable.selectAll()
            .where { GrammarTopicTable.id inList ids.distinct() }
            .associate {
                it[GrammarTopicTable.id].value to GrammarTopicRef(
                    id = it[GrammarTopicTable.id].value.toString(),
                    name = it[GrammarTopicTable.name],
                    level = it[GrammarTopicTable.level],
                )
            }
    }
}

/** A uuid from a request body; a malformed one is treated as an unknown id (404 [notFoundCode]). */
fun parseUuid(raw: String, notFoundCode: String): UUID = try {
    UUID.fromString(raw)
} catch (_: IllegalArgumentException) {
    throw NotFoundException("Not found", code = notFoundCode)
}
