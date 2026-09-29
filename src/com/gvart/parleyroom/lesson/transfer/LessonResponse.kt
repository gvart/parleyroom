package com.gvart.parleyroom.lesson.transfer

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import com.gvart.parleyroom.document.transfer.LessonDocumentRef
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.topic.transfer.GrammarTopicRef
import com.gvart.parleyroom.topic.transfer.TopicRef
import com.gvart.parleyroom.vocabulary.transfer.VocabDisplaySetting
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class LessonResponse(
    val id: String,
    val title: String,
    val type: LessonType,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val scheduledAt: OffsetDateTime,
    val durationMinutes: Int,
    val teacherId: String,
    val teacher: LessonTeacherResponse,
    val status: LessonStatus,
    val topic: String,
    val level: LanguageLevel? = null,
    val maxParticipants: Int? = null,
    val groupId: String? = null,
    val students: List<LessonStudentResponse> = emptyList(),
    @Serializable(with = OffsetDateTimeSerializer::class)
    val startedAt: OffsetDateTime? = null,
    val pendingReschedule: PendingRescheduleResponse? = null,
    /** Set while status is CANCELLED. */
    val cancelReason: String? = null,
    val cancelledBy: String? = null,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val cancelledAt: OffsetDateTime? = null,
    /** Block documents linked to the lesson; clients refetch one when its updatedAt changes. */
    val documents: List<LessonDocumentRef> = emptyList(),
    /** The teacher's plain-text lesson notes (live classroom + Nachbereitung). Teacher-only: null for students. */
    val rawNotes: String? = null,
    /** Teacher-only: null for students. */
    val promptUsed: String? = null,
    val topics: List<TopicRef> = emptyList(),
    val grammarTopics: List<GrammarTopicRef> = emptyList(),
    val vocab: List<LessonVocabRef> = emptyList(),
    val correctedSentences: List<CorrectedSentenceResponse> = emptyList(),
    val vocabDisplayOverride: VocabDisplaySetting? = null,
    val createdBy: String,
    val updatedBy: String? = null,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val updatedAt: OffsetDateTime,
)

@Serializable
data class PendingRescheduleResponse(
    @Serializable(with = OffsetDateTimeSerializer::class)
    val newScheduledAt: OffsetDateTime,
    val note: String? = null,
    val requestedBy: String,
)

@Serializable
data class LessonStudentResponse(
    val id: String,
    val firstName: String,
    val lastName: String,
    val status: String,
)

@Serializable
data class LessonTeacherResponse(
    val id: String,
    val firstName: String,
    val lastName: String,
)
