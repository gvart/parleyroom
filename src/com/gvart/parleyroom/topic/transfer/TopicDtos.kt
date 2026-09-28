package com.gvart.parleyroom.topic.transfer

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class TopicResponse(
    val id: String,
    val teacherId: String,
    val parentId: String? = null,
    val name: String,
    val levels: List<LanguageLevel> = emptyList(),
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
)

@Serializable
data class CreateTopicRequest(
    val name: String,
    val parentId: String? = null,
    val levels: List<LanguageLevel> = emptyList(),
) {
    fun validate(): ValidationResult =
        if (name.isBlank()) ValidationResult.Invalid("Name can't be empty") else ValidationResult.Valid
}

/**
 * PATCH-like: null fields are left unchanged. `moveToRoot = true` clears the parent
 * (a null parentId alone means "keep the current parent").
 */
@Serializable
data class UpdateTopicRequest(
    val name: String? = null,
    val parentId: String? = null,
    val moveToRoot: Boolean = false,
    val levels: List<LanguageLevel>? = null,
) {
    fun validate(): ValidationResult =
        if (name != null && name.isBlank()) ValidationResult.Invalid("Name can't be blank") else ValidationResult.Valid
}

/** Compact reference used when other resources (lessons, vocab) embed topics. */
@Serializable
data class TopicRef(
    val id: String,
    val name: String,
)
