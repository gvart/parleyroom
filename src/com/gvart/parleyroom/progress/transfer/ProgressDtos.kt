package com.gvart.parleyroom.progress.transfer

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import com.gvart.parleyroom.library.transfer.CoverageSource
import com.gvart.parleyroom.progress.data.GrammarProgressStatus
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class ProgressThresholds(val needsWorkBelow: Double, val minScoredItems: Int, val window: Int)

@Serializable
data class ScoredCount(val correct: Int, val total: Int)

@Serializable
data class GrammarEvidence(
    val lessonCount: Int,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val lastLessonAt: OffsetDateTime? = null,
    val homeworkScored: ScoredCount,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val lastPracticedAt: OffsetDateTime? = null,
)

@Serializable
data class GrammarOverride(
    val status: GrammarProgressStatus,
    /** Teachers/admins only; null for the student. */
    val note: String? = null,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val updatedAt: OffsetDateTime,
    val updatedBy: String,
)

@Serializable
data class GrammarProgressItem(
    val id: String,
    val name: String,
    val level: LanguageLevel? = null,
    val category: String? = null,
    val position: Int,
    val derived: GrammarProgressStatus,
    val override: GrammarOverride? = null,
    val effective: GrammarProgressStatus,
    val evidence: GrammarEvidence,
)

@Serializable
data class GrammarPercent(val notCovered: Int, val covered: Int, val practiced: Int, val needsWork: Int, val reached: Int)

@Serializable
data class GrammarCounts(
    val total: Int,
    val notCovered: Int,
    val covered: Int,
    val practiced: Int,
    val needsWork: Int,
    val percent: GrammarPercent? = null,
)

@Serializable
data class GrammarProgressLevel(
    val level: LanguageLevel,
    val checklistEmpty: Boolean,
    val counts: GrammarCounts,
    val items: List<GrammarProgressItem>,
)

@Serializable
data class WordCounts(val learned: Int, val total: Int)

@Serializable
data class TopicProgressItem(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val levels: List<LanguageLevel>,
    val path: List<String>,
    val covered: Boolean,
    val via: List<CoverageSource>,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val lastLessonAt: OffsetDateTime? = null,
    val words: WordCounts,
)

@Serializable
data class CoveredCount(val total: Int, val covered: Int, val percent: Int? = null)

@Serializable
data class VocabCount(val total: Int, val learned: Int, val percent: Int? = null)

@Serializable
data class StreakSummary(val current: Int, val longest: Int, val todayDone: Boolean)

@Serializable
data class ProgressSummary(
    val grammar: GrammarCounts,
    val topics: CoveredCount,
    val vocab: VocabCount,
    val streak: StreakSummary,
)

@Serializable
data class StudentProgress(
    val studentId: String,
    val teacherId: String,
    val level: LanguageLevel? = null,
    val levels: List<LanguageLevel>,
    val checklistEmpty: Boolean,
    val thresholds: ProgressThresholds,
    val grammar: List<GrammarProgressLevel>,
    val topics: List<TopicProgressItem>,
    val summary: ProgressSummary,
)

/** Raw strings so a bad value is a 400 GRAMMAR_OVERRIDE_INVALID with a pointer, not MALFORMED_REQUEST. */
@Serializable
data class GrammarOverrideRequest(val status: String? = null, val note: String? = null)
