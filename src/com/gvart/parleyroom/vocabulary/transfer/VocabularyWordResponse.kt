package com.gvart.parleyroom.vocabulary.transfer

import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import com.gvart.parleyroom.vocabulary.data.VocabCategory
import com.gvart.parleyroom.vocabulary.data.VocabStatus
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class VocabularyWordResponse(
    val id: String,
    val studentId: String,
    val lessonId: String? = null,
    val german: String,
    val english: String,
    val exampleSentence: String? = null,
    val exampleTranslation: String? = null,
    val category: VocabCategory,
    val status: VocabStatus,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val nextReviewAt: OffsetDateTime? = null,
    val reviewCount: Int,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val addedAt: OffsetDateTime,
)
