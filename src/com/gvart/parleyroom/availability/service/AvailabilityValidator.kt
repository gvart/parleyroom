package com.gvart.parleyroom.availability.service

import com.gvart.parleyroom.availability.data.AvailabilityExceptionType
import com.gvart.parleyroom.availability.data.TeacherAvailabilityExceptionTable
import com.gvart.parleyroom.availability.data.TeacherWeeklyAvailabilityTable
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserTable
import kotlinx.datetime.toJavaLocalTime
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/**
 * Validates that a prospective booking falls inside a teacher's effective availability
 * (weekly ∪ AVAILABLE − BLOCKED) and respects the min-notice window.
 *
 * Buffer-against-existing-lessons is enforced separately in LessonSupport.checkTeacherOverlap
 * (already widened to accept bufferMinutes).
 *
 * Error codes returned via BadRequestException.code:
 *   AVAILABILITY_SLOT_BLOCKED — outside an effective window (or inside a BLOCKED exception)
 *   AVAILABILITY_MIN_NOTICE   — too close to now
 */
class AvailabilityValidator {

    data class Settings(
        val timezone: ZoneId,
        val bufferMinutes: Int,
        val minNoticeHours: Int,
    )

    fun loadSettings(teacherId: UUID): Settings {
        val row = UserTable.selectAll()
            .where { (UserTable.id eq teacherId) and (UserTable.role eq UserRole.TEACHER) }
            .singleOrNull() ?: throw NotFoundException("Teacher not found")
        return Settings(
            timezone = ZoneId.of(row[UserTable.timezone]),
            bufferMinutes = row[UserTable.bookingBufferMinutes] ?: 0,
            minNoticeHours = row[UserTable.bookingMinNoticeHours] ?: 0,
        )
    }

    fun validate(
        teacherId: UUID,
        scheduledAt: OffsetDateTime,
        durationMinutes: Int,
        now: OffsetDateTime,
    ) = transaction {
        val settings = loadSettings(teacherId)
        val end = scheduledAt.plusMinutes(durationMinutes.toLong())

        // Min-notice check.
        val earliestAllowed = now.plusHours(settings.minNoticeHours.toLong())
        if (scheduledAt.isBefore(earliestAllowed)) {
            throw BadRequestException(
                "Booking must be at least ${settings.minNoticeHours}h from now",
                code = "AVAILABILITY_MIN_NOTICE",
            )
        }

        val interval = Interval(scheduledAt, end)

        val weeklyWindows = buildWeeklyWindowsFor(teacherId, settings.timezone, interval)
        val availableOverrides = loadExceptions(teacherId, interval, AvailabilityExceptionType.AVAILABLE)
        val blockedExceptions = loadExceptions(teacherId, interval, AvailabilityExceptionType.BLOCKED)

        // Default-open policy: a teacher who hasn't configured a weekly schedule
        // (and has no AVAILABLE overrides) accepts bookings at any time. BLOCKED
        // exceptions still bite so teachers can mark vacations without having to
        // first build out a full weekly schedule.
        val hasWeeklyConfig = weeklyWindows.isNotEmpty() || availableOverrides.isNotEmpty()

        if (hasWeeklyConfig) {
            val effective = subtractAll(
                mergeIntervals(weeklyWindows + availableOverrides),
                blockedExceptions,
            )
            val covered = effective.any { !it.start.isAfter(interval.start) && !it.end.isBefore(interval.end) }
            if (!covered) {
                throw BadRequestException(
                    "Requested time is outside the teacher's availability",
                    code = "AVAILABILITY_SLOT_BLOCKED",
                )
            }
        } else {
            // Only check BLOCKED exceptions when there's no positive configuration.
            val hitsBlocker = blockedExceptions.any { it.intersect(interval) != null }
            if (hitsBlocker) {
                throw BadRequestException(
                    "Requested time is blocked on the teacher's calendar",
                    code = "AVAILABILITY_SLOT_BLOCKED",
                )
            }
        }
    }

    private fun buildWeeklyWindowsFor(teacherId: UUID, tz: ZoneId, interval: Interval): List<Interval> {
        val rows = TeacherWeeklyAvailabilityTable.selectAll()
            .where { TeacherWeeklyAvailabilityTable.teacherId eq teacherId }
            .map {
                Triple(
                    it[TeacherWeeklyAvailabilityTable.dayOfWeek].toInt(),
                    it[TeacherWeeklyAvailabilityTable.startTime].toJavaLocalTime(),
                    it[TeacherWeeklyAvailabilityTable.endTime].toJavaLocalTime(),
                )
            }
        if (rows.isEmpty()) return emptyList()

        val startDate = interval.start.atZoneSameInstant(tz).toLocalDate()
        val endDate = interval.end.atZoneSameInstant(tz).toLocalDate()

        val out = mutableListOf<Interval>()
        var d = startDate
        while (!d.isAfter(endDate)) {
            val iso = d.dayOfWeek.value
            for ((dow, s, e) in rows) {
                if (dow != iso) continue
                val wStart = ZonedDateTime.of(d, s, tz).toOffsetDateTime()
                val wEnd = ZonedDateTime.of(d, e, tz).toOffsetDateTime()
                out.add(Interval(wStart, wEnd))
            }
            d = d.plusDays(1)
        }
        return out
    }

    private fun loadExceptions(
        teacherId: UUID,
        interval: Interval,
        type: AvailabilityExceptionType,
    ): List<Interval> {
        return TeacherAvailabilityExceptionTable.selectAll()
            .where {
                (TeacherAvailabilityExceptionTable.teacherId eq teacherId) and
                        (TeacherAvailabilityExceptionTable.type eq type) and
                        (TeacherAvailabilityExceptionTable.endAt greater interval.start) and
                        (TeacherAvailabilityExceptionTable.startAt less interval.end)
            }
            .map {
                Interval(
                    it[TeacherAvailabilityExceptionTable.startAt],
                    it[TeacherAvailabilityExceptionTable.endAt],
                )
            }
    }
}
