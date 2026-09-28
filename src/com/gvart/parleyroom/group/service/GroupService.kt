package com.gvart.parleyroom.group.service

import com.gvart.parleyroom.common.service.AuthorizationHelper
import com.gvart.parleyroom.common.service.findByIdOrThrow
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.group.data.GroupMemberTable
import com.gvart.parleyroom.group.data.GroupTable
import com.gvart.parleyroom.group.transfer.GroupMemberResponse
import com.gvart.parleyroom.group.transfer.GroupMembersRequest
import com.gvart.parleyroom.group.transfer.GroupRequest
import com.gvart.parleyroom.group.transfer.GroupResponse
import com.gvart.parleyroom.topic.service.LibraryAccess
import com.gvart.parleyroom.user.data.TeacherStudentTable
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.UserPrincipal
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime
import java.util.UUID

class GroupService {

    fun listGroups(principal: UserPrincipal): List<GroupResponse> = transaction {
        if (principal.role == UserRole.STUDENT) throw ForbiddenException("Only teachers can list groups")
        val query = GroupTable.selectAll()
        if (principal.role == UserRole.TEACHER) query.andWhere { GroupTable.teacherId eq principal.id }
        toResponses(query.orderBy(GroupTable.name).toList())
    }

    fun getGroup(groupId: UUID, principal: UserPrincipal): GroupResponse = transaction {
        toResponses(listOf(requireOwnedGroup(groupId, principal))).single()
    }

    fun createGroup(request: GroupRequest, principal: UserPrincipal): GroupResponse = transaction {
        LibraryAccess.requireTeacher(principal)
        val studentIds = requireOwnStudents(principal.id, request.studentIds)
        val now = OffsetDateTime.now()
        val id = GroupTable.insertAndGetId {
            it[teacherId] = principal.id
            it[name] = request.name.trim()
            it[level] = request.level
            it[type] = request.type
            it[createdAt] = now
            it[updatedAt] = now
        }.value
        addMembers(id, studentIds)
        getGroupRow(id).let { toResponses(listOf(it)).single() }
    }

    fun updateGroup(groupId: UUID, request: GroupRequest, principal: UserPrincipal): GroupResponse = transaction {
        requireOwnedGroup(groupId, principal)
        GroupTable.update({ GroupTable.id eq groupId }) {
            it[name] = request.name.trim()
            it[level] = request.level
            it[type] = request.type
        }
        toResponses(listOf(getGroupRow(groupId))).single()
    }

    fun deleteGroup(groupId: UUID, principal: UserPrincipal) = transaction {
        requireOwnedGroup(groupId, principal)
        GroupTable.deleteWhere { id eq groupId }
    }

    fun replaceMembers(groupId: UUID, request: GroupMembersRequest, principal: UserPrincipal): GroupResponse = transaction {
        val group = requireOwnedGroup(groupId, principal)
        val studentIds = requireOwnStudents(group[GroupTable.teacherId].value, request.studentIds)
        GroupMemberTable.deleteWhere { GroupMemberTable.groupId eq groupId }
        addMembers(groupId, studentIds)
        toResponses(listOf(group)).single()
    }

    fun addMembersTo(groupId: UUID, request: GroupMembersRequest, principal: UserPrincipal): GroupResponse = transaction {
        val group = requireOwnedGroup(groupId, principal)
        val studentIds = requireOwnStudents(group[GroupTable.teacherId].value, request.studentIds)
        val existing = memberIds(groupId).toSet()
        addMembers(groupId, studentIds.filter { it !in existing })
        toResponses(listOf(group)).single()
    }

    fun removeMember(groupId: UUID, studentId: UUID, principal: UserPrincipal): GroupResponse = transaction {
        val group = requireOwnedGroup(groupId, principal)
        GroupMemberTable.deleteWhere { (GroupMemberTable.groupId eq groupId) and (GroupMemberTable.studentId eq studentId) }
        toResponses(listOf(group)).single()
    }

    /** Loads a group and checks the principal owns it (or is admin). Must be called inside a transaction. */
    fun requireOwnedGroup(groupId: UUID, principal: UserPrincipal): ResultRow {
        val row = getGroupRow(groupId)
        AuthorizationHelper.requireOwnerOrAdmin(row[GroupTable.teacherId].value, principal, "Not your group")
        return row
    }

    fun memberIds(groupId: UUID): List<UUID> =
        GroupMemberTable.selectAll()
            .where { GroupMemberTable.groupId eq groupId }
            .map { it[GroupMemberTable.studentId].value }

    private fun getGroupRow(groupId: UUID): ResultRow = GroupTable.findByIdOrThrow(groupId, "Group")

    private fun requireOwnStudents(teacherId: UUID, ids: List<String>): List<UUID> {
        val uuids = ids.map(UUID::fromString).distinct()
        if (uuids.isEmpty()) return uuids
        val linked = TeacherStudentTable.selectAll()
            .where { (TeacherStudentTable.teacherId eq teacherId) and (TeacherStudentTable.studentId inList uuids) }
            .map { it[TeacherStudentTable.studentId].value }
            .toSet()
        val unlinked = uuids.filter { it !in linked }
        if (unlinked.isNotEmpty())
            throw BadRequestException(
                "Teacher does not have a relationship with students: ${unlinked.joinToString()}",
                code = "STUDENT_NOT_LINKED",
            )
        return uuids
    }

    private fun addMembers(groupId: UUID, studentIds: List<UUID>) {
        if (studentIds.isEmpty()) return
        val now = OffsetDateTime.now()
        GroupMemberTable.batchInsert(studentIds) { studentId ->
            this[GroupMemberTable.groupId] = groupId
            this[GroupMemberTable.studentId] = studentId
            this[GroupMemberTable.addedAt] = now
        }
    }

    private fun toResponses(rows: List<ResultRow>): List<GroupResponse> {
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { it[GroupTable.id].value }
        val membersByGroup = GroupMemberTable
            .join(UserTable, JoinType.INNER, GroupMemberTable.studentId, UserTable.id)
            .selectAll()
            .where { GroupMemberTable.groupId inList ids }
            .orderBy(UserTable.firstName)
            .groupBy({ it[GroupMemberTable.groupId].value }) {
                GroupMemberResponse(
                    id = it[UserTable.id].value.toString(),
                    firstName = it[UserTable.firstName],
                    lastName = it[UserTable.lastName],
                    level = it[UserTable.level],
                )
            }
        return rows.map { row ->
            GroupResponse(
                id = row[GroupTable.id].value.toString(),
                teacherId = row[GroupTable.teacherId].value.toString(),
                name = row[GroupTable.name],
                level = row[GroupTable.level],
                type = row[GroupTable.type],
                members = membersByGroup[row[GroupTable.id].value] ?: emptyList(),
                createdAt = row[GroupTable.createdAt],
            )
        }
    }
}
