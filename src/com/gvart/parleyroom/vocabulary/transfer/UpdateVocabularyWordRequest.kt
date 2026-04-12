package com.gvart.parleyroom.vocabulary.transfer

import com.gvart.parleyroom.vocabulary.data.VocabCategory
import kotlinx.serialization.Serializable

@Serializable
data class UpdateVocabularyWordRequest(
    val german: String? = null,
    val english: String? = null,
    val exampleSentence: String? = null,
    val exampleTranslation: String? = null,
    val category: VocabCategory? = null,
)
