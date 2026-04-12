package com.gvart.parleyroom.user.service

import com.gvart.parleyroom.registration.data.RegistrationTable
import com.gvart.parleyroom.user.data.TeacherStudentTable
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.UserPrincipal
import com.gvart.parleyroom.user.transfer.UserListResponse
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

class UserService {

    //TODO add pagination
    fun findAllUsers(principal: UserPrincipal): UserListResponse = transaction {
        val users = when (principal.role) {
            UserRole.ADMIN -> UserTable.selectAll()
            UserRole.TEACHER -> UserTable.join(RegistrationTable, JoinType.INNER)
            { UserTable.email eq RegistrationTable.email }
                .selectAll()
                .where { RegistrationTable.invitedBy eq principal.id }
            UserRole.STUDENT -> UserTable.join(TeacherStudentTable, JoinType.INNER)
            { UserTable.id eq TeacherStudentTable.teacherId }
                .selectAll()
                .where { TeacherStudentTable.studentId eq principal.id }
        }

        UserListResponse(
            users = users.map { UserListResponse.User(
                it[UserTable.id].value.toString(),
                it[UserTable.firstName],
                it[UserTable.lastName],
                it[UserTable.initials],
                it[UserTable.role],
                it[UserTable.avatarUrl],
                it[UserTable.level],
                it[UserTable.status],
                it[UserTable.createdAt].toString(),
                it[UserTable.locale],

            ) }
        )
    }
}