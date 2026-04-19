package com.gvart.parleyroom.lesson.transfer

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import com.gvart.parleyroom.lesson.data.LessonStatus
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class PublicCalendarResponse(
    val teacher: PublicTeacher,
    val lessons: List<PublicLesson>,
)

@Serializable
data class PublicTeacher(
    val firstName: String,
    val lastName: String,
)

/**
 * Scrubbed lesson shape used by the unauthenticated public calendar page.
 * 1:1 slots collapse to a plain "busy" block; group clubs expose their
 * promotional metadata (title, topic, type, spots) so prospective students
 * can see what's scheduled.
 */
@Serializable
data class PublicLesson(
    val id: String,
    val type: LessonType,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val scheduledAt: OffsetDateTime,
    val durationMinutes: Int,
    val status: LessonStatus,
    /** null for 1:1 slots; group lessons expose their title. */
    val title: String? = null,
    /** null for 1:1 slots; group lessons expose their topic. */
    val topic: String? = null,
    val level: LanguageLevel? = null,
    val participantCount: Int = 0,
    val maxParticipants: Int? = null,
)
