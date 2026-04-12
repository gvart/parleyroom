package com.gvart.parleyroom.common.service

import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.user.data.TeacherStudentTable
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.security.UserPrincipal
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import java.util.UUID

object AuthorizationHelper {

    fun requireAccessToStudent(studentId: UUID, principal: UserPrincipal) {
        if (principal.role == UserRole.ADMIN) return
        if (principal.role == UserRole.STUDENT) {
            if (principal.id != studentId)
                throw ForbiddenException("Students can only access their own data")
            return
        }
        // TEACHER — must have teacher_students relationship
        val hasRelationship = TeacherStudentTable.selectAll()
            .where {
                (TeacherStudentTable.teacherId eq principal.id) and
                        (TeacherStudentTable.studentId eq studentId)
            }
            .empty().not()

        if (!hasRelationship)
            throw ForbiddenException("No teacher-student relationship with this student")
    }
}