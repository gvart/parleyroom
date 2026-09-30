package com.gvart.parleyroom.lesson.transfer

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import com.gvart.parleyroom.lesson.data.LessonStudentStatus
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

/**
 * An upcoming club as a student browsing clubs sees it. Other participants
 * are only counted, never named.
 */
@Serializable
data class OpenClubResponse(
    val id: String,
    val title: String,
    val type: LessonType,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val scheduledAt: OffsetDateTime,
    val durationMinutes: Int,
    val topic: String,
    val level: LanguageLevel? = null,
    val teacher: LessonTeacherResponse,
    /** null = unlimited. */
    val maxParticipants: Int? = null,
    /** Confirmed participants plus pending requests; a pending request holds a spot. */
    val takenSpots: Int,
    /** The viewer's own participation; null when they haven't asked to join. */
    val myStatus: LessonStudentStatus? = null,
)
