package com.gvart.parleyroom.availability.transfer

import com.gvart.parleyroom.availability.data.AvailabilityExceptionType
import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

@Serializable
data class WeeklyAvailabilityEntry(
    val id: String? = null,
    val dayOfWeek: Int,
    val startTime: String,
    val endTime: String,
)

@Serializable
data class ReplaceWeeklyAvailabilityRequest(
    val entries: List<WeeklyAvailabilityEntry>,
) {
    fun validate(): ValidationResult {
        val errors = buildList {
            entries.forEachIndexed { idx, entry ->
                if (entry.dayOfWeek !in 1..7) {
                    add("entries[$idx].dayOfWeek must be between 1 and 7 (ISO)")
                }
                val start = runCatching { LocalTime.parse(entry.startTime) }.getOrNull()
                val end = runCatching { LocalTime.parse(entry.endTime) }.getOrNull()
                if (start == null) add("entries[$idx].startTime is not a valid HH:mm time")
                if (end == null) add("entries[$idx].endTime is not a valid HH:mm time")
                if (start != null && end != null && !start.isBefore(end)) {
                    add("entries[$idx].startTime must be before endTime")
                }
            }

            // Intra-day overlap check.
            val byDay = entries.groupBy { it.dayOfWeek }
            byDay.forEach { (day, list) ->
                val parsed = list.mapNotNull {
                    try {
                        LocalTime.parse(it.startTime) to LocalTime.parse(it.endTime)
                    } catch (_: DateTimeParseException) {
                        null
                    }
                }.sortedBy { it.first }
                for (i in 1 until parsed.size) {
                    if (parsed[i].first.isBefore(parsed[i - 1].second)) {
                        add("Day $day has overlapping ranges")
                        break
                    }
                }
            }
        }
        return if (errors.isNotEmpty()) ValidationResult.Invalid(errors) else ValidationResult.Valid
    }
}

@Serializable
data class AvailabilityExceptionResponse(
    val id: String,
    val type: AvailabilityExceptionType,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val startAt: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val endAt: OffsetDateTime,
    val reason: String? = null,
)

/** Public variant: no reason, no id. Used in unauthenticated calendar response. */
@Serializable
data class PublicAvailabilityException(
    val type: AvailabilityExceptionType,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val startAt: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val endAt: OffsetDateTime,
)

@Serializable
data class CreateAvailabilityExceptionRequest(
    val type: AvailabilityExceptionType,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val startAt: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val endAt: OffsetDateTime,
    val reason: String? = null,
) {
    fun validate(): ValidationResult {
        val errors = buildList {
            if (!startAt.isBefore(endAt)) add("startAt must be before endAt")
            if (reason != null && reason.length > 500) add("reason must be at most 500 characters")
        }
        return if (errors.isNotEmpty()) ValidationResult.Invalid(errors) else ValidationResult.Valid
    }
}

@Serializable
data class AvailableSlot(
    @Serializable(with = OffsetDateTimeSerializer::class)
    val start: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val end: OffsetDateTime,
)

@Serializable
data class AvailableSlotsResponse(
    val slots: List<AvailableSlot>,
)

/** Publicly exposed availability for a teacher — consumed by the public calendar page. */
@Serializable
data class PublicAvailability(
    val timezone: String,
    val weekly: List<WeeklyAvailabilityEntry>,
    val exceptions: List<PublicAvailabilityException>,
)
