package com.gvart.parleyroom.vocabulary.service

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.service.AuthorizationHelper
import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.user.data.TeacherStudentTable
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.data.requireSupportedNativeLanguage
import com.gvart.parleyroom.user.security.UserPrincipal
import com.gvart.parleyroom.vocabulary.transfer.VocabDisplaySetting
import com.gvart.parleyroom.vocabulary.transfer.VocabSettingsResponse
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

/** Per teacher–student vocab display setting and the student's level. */
class VocabSettingsService {

    fun getSettings(studentId: UUID, teacherId: UUID?, principal: UserPrincipal): VocabSettingsResponse = transaction {
        AuthorizationHelper.requireAccessToStudent(studentId, principal)
        toResponse(findRelationship(studentId, resolveTeacher(teacherId, principal)))
    }

    fun putSettings(studentId: UUID, setting: VocabDisplaySetting, principal: UserPrincipal): VocabSettingsResponse = transaction {
        requireTeacherOf(studentId, principal)
        VocabDisplay.requireSupportedFields(setting)
        TeacherStudentTable.update({ relationship(studentId, principal.id) }) {
            it[vocabDisplayFields] = setting.fields.distinct()
            it[allowTranslationToggle] = setting.allowTranslationToggle
        }
        toResponse(findRelationship(studentId, principal.id))
    }

    fun resetSettings(studentId: UUID, principal: UserPrincipal): VocabSettingsResponse = transaction {
        requireTeacherOf(studentId, principal)
        TeacherStudentTable.update({ relationship(studentId, principal.id) }) {
            it[vocabDisplayFields] = null
            it[allowTranslationToggle] = null
        }
        toResponse(findRelationship(studentId, principal.id))
    }

    fun setLevel(studentId: UUID, level: LanguageLevel, principal: UserPrincipal): VocabSettingsResponse = transaction {
        requireTeacherOf(studentId, principal)
        UserTable.update({ UserTable.id eq studentId }) { it[UserTable.level] = level }
        toResponse(findRelationship(studentId, principal.id))
    }

    fun setNativeLanguage(studentId: UUID, nativeLanguage: String, principal: UserPrincipal): VocabSettingsResponse = transaction {
        requireSupportedNativeLanguage(nativeLanguage)
        requireTeacherOf(studentId, principal)
        UserTable.update({ UserTable.id eq studentId }) { it[UserTable.nativeLanguage] = nativeLanguage }
        toResponse(findRelationship(studentId, principal.id))
    }

    private fun requireTeacherOf(studentId: UUID, principal: UserPrincipal) {
        if (principal.role != UserRole.TEACHER)
            throw ForbiddenException("Only the student's teacher can change these settings")
        AuthorizationHelper.requireAccessToStudent(studentId, principal)
    }

    private fun resolveTeacher(teacherId: UUID?, principal: UserPrincipal): UUID? = when (principal.role) {
        UserRole.TEACHER -> principal.id
        UserRole.STUDENT, UserRole.ADMIN -> teacherId
    }

    /** With no explicit teacher (student/admin), falls back to the student's earliest teacher. */
    private fun findRelationship(studentId: UUID, teacherId: UUID?): ResultRow {
        val query = TeacherStudentTable.selectAll().where { TeacherStudentTable.studentId eq studentId }
        if (teacherId != null) query.andWhere { TeacherStudentTable.teacherId eq teacherId }
        return query.orderBy(TeacherStudentTable.startedAt).firstOrNull()
            ?: throw NotFoundException("Student has no teacher", code = "TEACHER_STUDENT_NOT_FOUND")
    }

    private fun relationship(studentId: UUID, teacherId: UUID) =
        (TeacherStudentTable.studentId eq studentId) and (TeacherStudentTable.teacherId eq teacherId)

    private fun toResponse(row: ResultRow): VocabSettingsResponse {
        val studentId = row[TeacherStudentTable.studentId].value
        val student = UserTable.selectAll().where { UserTable.id eq studentId }.single()
        val level = student[UserTable.level]
        val nativeLanguage = student[UserTable.nativeLanguage]
        val stored = VocabDisplay.of(row[TeacherStudentTable.vocabDisplayFields], row[TeacherStudentTable.allowTranslationToggle])
        val effective = stored ?: VocabDisplay.defaultFor(level, nativeLanguage)
        return VocabSettingsResponse(
            studentId = studentId.toString(),
            teacherId = row[TeacherStudentTable.teacherId].value.toString(),
            level = level,
            nativeLanguage = nativeLanguage,
            fields = effective.fields,
            allowTranslationToggle = effective.allowTranslationToggle,
            isDefault = stored == null,
        )
    }
}
