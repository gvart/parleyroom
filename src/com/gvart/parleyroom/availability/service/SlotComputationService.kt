package com.gvart.parleyroom.availability.service

import com.gvart.parleyroom.availability.data.AvailabilityExceptionType
import com.gvart.parleyroom.availability.data.TeacherAvailabilityExceptionTable
import com.gvart.parleyroom.availability.data.TeacherWeeklyAvailabilityTable
import com.gvart.parleyroom.availability.transfer.AvailableSlot
import com.gvart.parleyroom.availability.transfer.AvailableSlotsResponse
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.user.data.TeacherStudentTable
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserStatus
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.UserPrincipal
import kotlinx.datetime.toJavaLocalTime
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/**
 * Computes bookable slots for a teacher within a date window.
 *
 * Algorithm:
 *   1. load teacher settings (tz, buffer, notice), weekly rows, exceptions in window,
 *      non-CANCELLED lessons overlapping window.
 *   2. cutoff = max(from, now + minNoticeHours)
 *   3. for each date in [cutoff.date(tz) .. to.date(tz)]:
 *        for each weekly row matching date.dayOfWeek (ISO 1=Mon..7=Sun):
 *            windowStart = ZonedDateTime.of(date, startTime, tz)
 *            windowEnd   = ZonedDateTime.of(date, endTime,   tz)
 *            intersect with [cutoff, to] and add to windows
 *   4. union AVAILABLE exceptions (merge overlaps)
 *   5. subtract BLOCKED exceptions
 *   6. subtract each lesson widened by bufferMinutes on both sides
 *   7. chunk each remaining window into fixed durationMinutes slots (step = duration)
 *   8. return sorted by start
 */
class SlotComputationService {

    fun getSlots(
        teacherId: UUID,
        from: OffsetDateTime,
        to: OffsetDateTime,
        durationMinutes: Int,
        now: OffsetDateTime,
        principal: UserPrincipal,
    ): AvailableSlotsResponse = transaction {
        requireReadAccess(teacherId, principal)

        if (durationMinutes <= 0) throw BadRequestException("durationMinutes must be positive")
        if (!from.isBefore(to)) throw BadRequestException("from must be before to")

        val teacherRow = UserTable.selectAll()
            .where { (UserTable.id eq teacherId) and (UserTable.role eq UserRole.TEACHER) }
            .singleOrNull() ?: throw NotFoundException("Teacher not found")

        val tz = ZoneId.of(teacherRow[UserTable.timezone])
        val bufferMinutes = teacherRow[UserTable.bookingBufferMinutes] ?: 0
        val minNoticeHours = teacherRow[UserTable.bookingMinNoticeHours] ?: 0

        val cutoff = maxOf(from, now.plusHours(minNoticeHours.toLong()))
        if (!cutoff.isBefore(to)) return@transaction AvailableSlotsResponse(emptyList())

        // 1. Expand weekly availability across the window in teacher's tz.
        val weeklyRows = TeacherWeeklyAvailabilityTable.selectAll()
            .where { TeacherWeeklyAvailabilityTable.teacherId eq teacherId }
            .map {
                Triple(
                    it[TeacherWeeklyAvailabilityTable.dayOfWeek].toInt(),
                    it[TeacherWeeklyAvailabilityTable.startTime].toJavaLocalTime(),
                    it[TeacherWeeklyAvailabilityTable.endTime].toJavaLocalTime(),
                )
            }

        val startDate = cutoff.atZoneSameInstant(tz).toLocalDate()
        val endDate = to.atZoneSameInstant(tz).toLocalDate()

        var windows = mutableListOf<Interval>()
        var date: LocalDate = startDate
        while (!date.isAfter(endDate)) {
            val isoDay = date.dayOfWeek.value  // 1..7
            for ((d, startTime, endTime) in weeklyRows) {
                if (d != isoDay) continue
                val start = ZonedDateTime.of(date, startTime, tz).toOffsetDateTime()
                val end = ZonedDateTime.of(date, endTime, tz).toOffsetDateTime()
                val clipped = Interval(start, end).intersect(Interval(cutoff, to))
                if (clipped != null) windows.add(clipped)
            }
            date = date.plusDays(1)
        }

        // 2. Union AVAILABLE exceptions + subtract BLOCKED exceptions (loaded within window).
        val exceptionRows = TeacherAvailabilityExceptionTable.selectAll()
            .where {
                (TeacherAvailabilityExceptionTable.teacherId eq teacherId) and
                        (TeacherAvailabilityExceptionTable.endAt greater cutoff) and
                        (TeacherAvailabilityExceptionTable.startAt less to)
            }
            .map {
                it[TeacherAvailabilityExceptionTable.type] to Interval(
                    it[TeacherAvailabilityExceptionTable.startAt],
                    it[TeacherAvailabilityExceptionTable.endAt],
                )
            }

        val availableExceptions = exceptionRows
            .filter { it.first == AvailabilityExceptionType.AVAILABLE }
            .mapNotNull { it.second.intersect(Interval(cutoff, to)) }
        val blockedExceptions = exceptionRows
            .filter { it.first == AvailabilityExceptionType.BLOCKED }
            .mapNotNull { it.second.intersect(Interval(cutoff, to)) }

        windows.addAll(availableExceptions)
        windows = mergeIntervals(windows).toMutableList()
        windows = subtractAll(windows, blockedExceptions).toMutableList()

        // 3. Subtract existing lessons widened by buffer.
        val lessonRows = LessonTable.selectAll()
            .where {
                (LessonTable.teacherId eq teacherId) and
                        (LessonTable.status neq LessonStatus.CANCELLED)
            }
            .map {
                val lessonStart = it[LessonTable.scheduledAt]
                val lessonEnd = lessonStart.plusMinutes(it[LessonTable.durationMinutes].toLong())
                Interval(
                    lessonStart.minusMinutes(bufferMinutes.toLong()),
                    lessonEnd.plusMinutes(bufferMinutes.toLong()),
                )
            }
            .filter { it.endsAfter(cutoff) && it.startsBefore(to) }

        windows = subtractAll(windows, lessonRows).toMutableList()

        // 4. Chunk into fixed slots.
        val slots = mutableListOf<AvailableSlot>()
        for (window in windows) {
            var slotStart = window.start
            while (true) {
                val slotEnd = slotStart.plusMinutes(durationMinutes.toLong())
                if (slotEnd.isAfter(window.end)) break
                slots.add(AvailableSlot(start = slotStart, end = slotEnd))
                slotStart = slotEnd
            }
        }

        AvailableSlotsResponse(slots = slots.sortedBy { it.start })
    }

    private fun requireReadAccess(teacherId: UUID, principal: UserPrincipal) {
        when (principal.role) {
            UserRole.ADMIN -> Unit
            UserRole.TEACHER -> {
                if (principal.id != teacherId) throw ForbiddenException("Cannot view another teacher's slots")
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
}

internal data class Interval(val start: OffsetDateTime, val end: OffsetDateTime) {
    init {
        require(!start.isAfter(end)) { "Interval start must be <= end" }
    }

    fun intersect(other: Interval): Interval? {
        val s = if (start.isAfter(other.start)) start else other.start
        val e = if (end.isBefore(other.end)) end else other.end
        return if (s.isBefore(e)) Interval(s, e) else null
    }

    fun startsBefore(t: OffsetDateTime): Boolean = start.isBefore(t)
    fun endsAfter(t: OffsetDateTime): Boolean = end.isAfter(t)
}

internal fun mergeIntervals(input: List<Interval>): List<Interval> {
    if (input.isEmpty()) return emptyList()
    val sorted = input.sortedBy { it.start }
    val merged = mutableListOf(sorted.first())
    for (i in 1 until sorted.size) {
        val last = merged.last()
        val cur = sorted[i]
        if (!cur.start.isAfter(last.end)) {
            merged[merged.lastIndex] = Interval(
                start = last.start,
                end = if (cur.end.isAfter(last.end)) cur.end else last.end,
            )
        } else {
            merged.add(cur)
        }
    }
    return merged
}

/** Subtract a single interval [s,e] from one interval w — produces 0, 1, or 2 pieces. */
internal fun subtractOne(w: Interval, cut: Interval): List<Interval> {
    val overlap = w.intersect(cut) ?: return listOf(w)
    val pieces = mutableListOf<Interval>()
    if (w.start.isBefore(overlap.start)) pieces.add(Interval(w.start, overlap.start))
    if (overlap.end.isBefore(w.end)) pieces.add(Interval(overlap.end, w.end))
    return pieces
}

internal fun subtractAll(windows: List<Interval>, cuts: List<Interval>): List<Interval> {
    var current = windows
    for (cut in cuts) {
        val next = mutableListOf<Interval>()
        for (w in current) next.addAll(subtractOne(w, cut))
        current = next
    }
    return current
}
