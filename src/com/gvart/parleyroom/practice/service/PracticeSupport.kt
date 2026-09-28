package com.gvart.parleyroom.practice.service

import com.gvart.parleyroom.common.service.findByIdOrThrow
import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.UserPrincipal
import com.gvart.parleyroom.vocabulary.data.StudentVocabTable
import org.jetbrains.exposed.v1.core.ResultRow
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

object PracticeAccess {

    fun studentOnly() = ForbiddenException("Only the student practises their own words", code = "PRACTICE_STUDENT_ONLY")

    /** Reviews and sentences change the student's own learning data: nobody else may write them. */
    fun requireOwnWord(row: ResultRow, principal: UserPrincipal) {
        if (principal.role != UserRole.STUDENT || row[StudentVocabTable.studentId].value != principal.id) throw studentOnly()
    }
}

object PracticeTime {

    /** Start of the student's current day in their timezone ("today" for budgets, stats and limits). */
    fun startOfDay(studentId: UUID, now: OffsetDateTime): OffsetDateTime {
        val zone = runCatching { ZoneId.of(UserTable.findByIdOrThrow(studentId, "User")[UserTable.timezone]) }
            .getOrDefault(ZoneOffset.UTC)
        return now.atZoneSameInstant(zone).toLocalDate().atStartOfDay(zone).toOffsetDateTime()
    }
}
