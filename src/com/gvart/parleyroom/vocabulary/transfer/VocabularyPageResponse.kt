package com.gvart.parleyroom.vocabulary.transfer

import kotlinx.serialization.Serializable

@Serializable
data class VocabularyPageResponse(
    val words: List<VocabularyWordResponse>,
    val total: Long,
    val page: Int,
    val pageSize: Int,
)
