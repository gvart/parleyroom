package com.gvart.parleyroom.practice.transfer

import com.gvart.parleyroom.activity.transfer.StreakResponse
import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import com.gvart.parleyroom.practice.data.PracticeMode
import com.gvart.parleyroom.practice.data.Rating
import com.gvart.parleyroom.vocabulary.data.NounArticle
import com.gvart.parleyroom.vocabulary.data.WordType
import com.gvart.parleyroom.vocabulary.transfer.StudentVocabResponse
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class ReviewRequest(
    val rating: Rating,
    val mode: PracticeMode,
    val responseMs: Int? = null,
)

@Serializable
data class ArticleCheckRequest(
    val article: NounArticle,
    val responseMs: Int? = null,
)

@Serializable
data class ArticleCheckResponse(
    val correct: Boolean,
    val correctArticle: NounArticle,
    val rating: Rating,
    val word: StudentVocabResponse,
)

/** When the card would be due next if rated now with this rating. */
@Serializable
data class IntervalPreview(
    @Serializable(with = OffsetDateTimeSerializer::class)
    val dueAt: OffsetDateTime,
    val seconds: Long,
)

@Serializable
data class PracticeCard(
    val mode: PracticeMode,
    val isNew: Boolean,
    val word: StudentVocabResponse,
    /** Per rating; ARTICLE mode only has AGAIN and GOOD. */
    val intervals: Map<Rating, IntervalPreview>,
)

@Serializable
data class PracticeQueueResponse(
    val mode: PracticeMode,
    val cards: List<PracticeCard>,
    val dueCount: Int,
    val newCount: Int,
    val newLimit: Int,
    val newIntroducedToday: Int,
)

@Serializable
data class PracticeStatsResponse(
    val dueNow: Int,
    val dueToday: Int,
    val newAvailable: Int,
    val newTotal: Int,
    val newLimit: Int,
    val newIntroducedToday: Int,
    val reviewedToday: Int,
    val sentencesToday: Int,
    val sentenceLimit: Int,
    val aiAvailable: Boolean,
    val streak: StreakResponse,
)

@Serializable
data class CreateSentenceRequest(val sentence: String)

@Serializable
data class ExplanationTranslation(val language: String, val text: String)

@Serializable
data class SentenceFeedback(
    val isCorrect: Boolean,
    val corrected: String,
    val explanation: String,
    val explanationTranslation: ExplanationTranslation? = null,
    val usesWord: Boolean,
)

@Serializable
data class SentenceWord(
    val lemma: String,
    val article: NounArticle? = null,
    val wordType: WordType,
)

@Serializable
data class SentenceResponse(
    val id: String,
    val studentVocabId: String,
    val studentId: String,
    val entryId: String,
    val word: SentenceWord,
    val sentence: String,
    val feedback: SentenceFeedback,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
)

@Serializable
data class SentencePageResponse(
    val sentences: List<SentenceResponse>,
    val total: Long,
    val page: Int,
    val pageSize: Int,
)
