package com.gvart.parleyroom.library.transfer

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import com.gvart.parleyroom.document.transfer.DocumentSummary
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.material.transfer.MaterialResponse
import com.gvart.parleyroom.topic.transfer.GrammarTopicResponse
import com.gvart.parleyroom.topic.transfer.TopicRef
import com.gvart.parleyroom.topic.transfer.TopicResponse
import com.gvart.parleyroom.vocabulary.transfer.VocabEntryResponse
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class LevelCounts(
    val level: LanguageLevel? = null,
    val words: Long,
    val documents: Long,
    val materials: Long,
    val topics: Long,
    val grammarTopics: Long,
)

@Serializable
data class LibraryTotals(
    val words: Long,
    val documents: Long,
    val materials: Long,
    val topics: Long,
    val grammarTopics: Long,
)

/** Distinct items tagged with a topic or any of its descendants. */
@Serializable
data class SubtreeCounts(
    val words: Long,
    val documents: Long,
    val materials: Long,
)

/** Counts of DIRECT tags on the topic, plus [subtree] totals. */
@Serializable
data class TopicCounts(
    val topicId: String,
    val words: Long,
    val documents: Long,
    val materials: Long,
    val lessons: Long,
    val coveredStudents: Long,
    val subtree: SubtreeCounts,
)

@Serializable
data class LibrarySummary(
    val levels: List<LevelCounts>,
    val totals: LibraryTotals,
    val topics: List<TopicCounts>,
)

@Serializable
data class LibraryLessonRef(
    val id: String,
    val title: String,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val scheduledAt: OffsetDateTime,
    val status: LessonStatus,
    val groupId: String? = null,
)

enum class CoverageSource { LESSON, VOCAB }

@Serializable
data class CoveredStudent(
    val studentId: String,
    val firstName: String,
    val lastName: String,
    val via: List<CoverageSource>,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val lastAt: OffsetDateTime,
)

@Serializable
data class TopicLibrary(
    val topic: TopicResponse,
    val path: List<TopicRef>,
    val children: List<TopicRef>,
    val words: List<VocabEntryResponse>,
    val documents: List<DocumentSummary>,
    val materials: List<MaterialResponse>,
    val lessons: List<LibraryLessonRef>,
    val coveredBy: List<CoveredStudent>,
)

@Serializable
data class GrammarChecklistItem(
    val grammarTopic: GrammarTopicResponse,
    val documents: Long,
    val materials: Long,
    val lessons: Long,
    val coveredStudents: Long,
)

@Serializable
data class GrammarLevelGroup(
    val level: LanguageLevel? = null,
    val topics: List<GrammarChecklistItem>,
)

@Serializable
data class GrammarTopicLibrary(
    val grammarTopic: GrammarTopicResponse,
    val documents: List<DocumentSummary>,
    val materials: List<MaterialResponse>,
    val lessons: List<LibraryLessonRef>,
    val coveredBy: List<CoveredStudent>,
)
