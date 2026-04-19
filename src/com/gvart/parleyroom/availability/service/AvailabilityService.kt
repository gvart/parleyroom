package com.gvart.parleyroom.availability.service

import com.gvart.parleyroom.availability.data.AvailabilityExceptionType
import com.gvart.parleyroom.availability.data.TeacherAvailabilityExceptionTable
import com.gvart.parleyroom.availability.data.TeacherWeeklyAvailabilityTable
import com.gvart.parleyroom.availability.transfer.AvailabilityExceptionResponse
import com.gvart.parleyroom.availability.transfer.CreateAvailabilityExceptionRequest
import com.gvart.parleyroom.availability.transfer.PublicAvailability
import com.gvart.parleyroom.availability.transfer.PublicAvailabilityException
import com.gvart.parleyroom.availability.transfer.ReplaceWeeklyAvailabilityRequest
import com.gvart.parleyroom.availability.transfer.WeeklyAvailabilityEntry
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserStatus
import com.gvart.parleyroom.user.data.TeacherStudentTable
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.UserPrincipal
import kotlinx.datetime.toJavaLocalTime
import kotlinx.datetime.toKotlinLocalTime
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.UUID

class AvailabilityService {

    fun getWeekly(teacherId: UUID, principal: UserPrincipal): List<WeeklyAvailabilityEntry> = transaction {
        requireReadAccess(teacherId, principal)
        TeacherWeeklyAvailabilityTable.selectAll()
            .where { TeacherWeeklyAvailabilityTable.teacherId eq teacherId }
            .orderBy(
                TeacherWeeklyAvailabilityTable.dayOfWeek to SortOrder.ASC,
                TeacherWeeklyAvailabilityTable.startTime to SortOrder.ASC,
            )
            .map { row ->
                WeeklyAvailabilityEntry(
                    id = row[TeacherWeeklyAvailabilityTable.id].value.toString(),
                    dayOfWeek = row[TeacherWeeklyAvailabilityTable.dayOfWeek].toInt(),
                    startTime = row[TeacherWeeklyAvailabilityTable.startTime].toJavaLocalTime().toString(),
                    endTime = row[TeacherWeeklyAvailabilityTable.endTime].toJavaLocalTime().toString(),
                )
            }
    }

    fun replaceWeekly(
        teacherId: UUID,
        request: ReplaceWeeklyAvailabilityRequest,
        principal: UserPrincipal,
    ): List<WeeklyAvailabilityEntry> = transaction {
        requireWriteAccess(teacherId, principal)
        requireTeacherRole(teacherId)

        TeacherWeeklyAvailabilityTable.deleteWhere { TeacherWeeklyAvailabilityTable.teacherId eq teacherId }

        if (request.entries.isNotEmpty()) {
            TeacherWeeklyAvailabilityTable.batchInsert(request.entries) { entry ->
                this[TeacherWeeklyAvailabilityTable.teacherId] = teacherId
                this[TeacherWeeklyAvailabilityTable.dayOfWeek] = entry.dayOfWeek.toShort()
                this[TeacherWeeklyAvailabilityTable.startTime] = LocalTime.parse(entry.startTime).toKotlinLocalTime()
                this[TeacherWeeklyAvailabilityTable.endTime] = LocalTime.parse(entry.endTime).toKotlinLocalTime()
                this[TeacherWeeklyAvailabilityTable.createdAt] = OffsetDateTime.now()
            }
        }

        getWeekly(teacherId, principal)
    }

    fun getExceptions(
        teacherId: UUID,
        from: OffsetDateTime?,
        to: OffsetDateTime?,
        principal: UserPrincipal,
    ): List<AvailabilityExceptionResponse> = transaction {
        requireReadAccess(teacherId, principal)
        val query = TeacherAvailabilityExceptionTable.selectAll()
            .where { TeacherAvailabilityExceptionTable.teacherId eq teacherId }
        if (from != null) query.andWhere { TeacherAvailabilityExceptionTable.endAt greater from }
        if (to != null) query.andWhere { TeacherAvailabilityExceptionTable.startAt less to }
        query
            .orderBy(TeacherAvailabilityExceptionTable.startAt to SortOrder.ASC)
            .map { row ->
                AvailabilityExceptionResponse(
                    id = row[TeacherAvailabilityExceptionTable.id].value.toString(),
                    type = row[TeacherAvailabilityExceptionTable.type],
                    startAt = row[TeacherAvailabilityExceptionTable.startAt],
                    endAt = row[TeacherAvailabilityExceptionTable.endAt],
                    reason = row[TeacherAvailabilityExceptionTable.reason],
                )
            }
    }

    fun createException(
        teacherId: UUID,
        request: CreateAvailabilityExceptionRequest,
        principal: UserPrincipal,
    ): AvailabilityExceptionResponse = transaction {
        requireWriteAccess(teacherId, principal)
        requireTeacherRole(teacherId)

        val now = OffsetDateTime.now()
        val id = TeacherAvailabilityExceptionTable.insertAndGetId {
            it[TeacherAvailabilityExceptionTable.teacherId] = teacherId
            it[TeacherAvailabilityExceptionTable.type] = request.type
            it[TeacherAvailabilityExceptionTable.startAt] = request.startAt
            it[TeacherAvailabilityExceptionTable.endAt] = request.endAt
            it[TeacherAvailabilityExceptionTable.reason] = request.reason?.takeIf { r -> r.isNotBlank() }
            it[TeacherAvailabilityExceptionTable.createdAt] = now
            it[TeacherAvailabilityExceptionTable.updatedAt] = now
        }

        AvailabilityExceptionResponse(
            id = id.value.toString(),
            type = request.type,
            startAt = request.startAt,
            endAt = request.endAt,
            reason = request.reason?.takeIf { it.isNotBlank() },
        )
    }

    fun deleteException(teacherId: UUID, exceptionId: UUID, principal: UserPrincipal) = transaction {
        requireWriteAccess(teacherId, principal)

        val deleted = TeacherAvailabilityExceptionTable.deleteWhere {
            (TeacherAvailabilityExceptionTable.id eq exceptionId) and
                    (TeacherAvailabilityExceptionTable.teacherId eq teacherId)
        }
        if (deleted == 0) throw NotFoundException("Exception not found")
    }

    /** Public (unauthenticated) read. No access guard, used by public calendar. */
    fun getPublicAvailability(teacherId: UUID, from: OffsetDateTime?, to: OffsetDateTime?): PublicAvailability = transaction {
        val teacherRow = UserTable.selectAll()
            .where { (UserTable.id eq teacherId) and (UserTable.role eq UserRole.TEACHER) }
            .singleOrNull() ?: throw NotFoundException("Teacher not found")

        val weekly = TeacherWeeklyAvailabilityTable.selectAll()
            .where { TeacherWeeklyAvailabilityTable.teacherId eq teacherId }
            .orderBy(
                TeacherWeeklyAvailabilityTable.dayOfWeek to SortOrder.ASC,
                TeacherWeeklyAvailabilityTable.startTime to SortOrder.ASC,
            )
            .map { row ->
                WeeklyAvailabilityEntry(
                    id = null,
                    dayOfWeek = row[TeacherWeeklyAvailabilityTable.dayOfWeek].toInt(),
                    startTime = row[TeacherWeeklyAvailabilityTable.startTime].toJavaLocalTime().toString(),
                    endTime = row[TeacherWeeklyAvailabilityTable.endTime].toJavaLocalTime().toString(),
                )
            }

        val exceptionsQuery = TeacherAvailabilityExceptionTable.selectAll()
            .where { TeacherAvailabilityExceptionTable.teacherId eq teacherId }
        if (from != null) exceptionsQuery.andWhere { TeacherAvailabilityExceptionTable.endAt greater from }
        if (to != null) exceptionsQuery.andWhere { TeacherAvailabilityExceptionTable.startAt less to }

        val exceptions = exceptionsQuery.map { row ->
            PublicAvailabilityException(
                type = row[TeacherAvailabilityExceptionTable.type],
                startAt = row[TeacherAvailabilityExceptionTable.startAt],
                endAt = row[TeacherAvailabilityExceptionTable.endAt],
            )
        }

        PublicAvailability(
            timezone = teacherRow[UserTable.timezone],
            weekly = weekly,
            exceptions = exceptions,
        )
    }

    private fun requireTeacherRole(teacherId: UUID) {
        val role = UserTable.selectAll()
            .where { UserTable.id eq teacherId }
            .singleOrNull()?.get(UserTable.role)
            ?: throw NotFoundException("Teacher not found")
        if (role != UserRole.TEACHER) throw BadRequestException("User is not a teacher")
    }

    /** Read access: teacher themselves, admin, or student with an ACTIVE link. */
    private fun requireReadAccess(teacherId: UUID, principal: UserPrincipal) {
        when (principal.role) {
            UserRole.ADMIN -> Unit
            UserRole.TEACHER -> {
                if (principal.id != teacherId) throw ForbiddenException("Cannot view another teacher's availability")
            }
            UserRole.STUDENT -> {
                val linked = TeacherStudentTable.selectAll()
                    .where {
                        (TeacherStudentTable.teacherId eq teacherId) and
                                (TeacherStudentTable.studentId eq principal.id) and
                                (TeacherStudentTable.status eq UserStatus.ACTIVE)
                    }
                    .empty().not()
                if (!linked) throw ForbiddenException("No active relationship with this teacher")
            }
        }
    }

    /** Write access: teacher themselves, or admin on-behalf. */
    private fun requireWriteAccess(teacherId: UUID, principal: UserPrincipal) {
        when (principal.role) {
            UserRole.ADMIN -> Unit
            UserRole.TEACHER -> {
                if (principal.id != teacherId) throw ForbiddenException("Cannot modify another teacher's availability")
            }
            UserRole.STUDENT -> throw ForbiddenException("Students cannot modify teacher availability")
        }
    }
}
