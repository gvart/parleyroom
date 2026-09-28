package com.gvart.parleyroom.topic.transfer

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class GrammarTopicResponse(
    val id: String,
    val teacherId: String,
    val name: String,
    val level: LanguageLevel? = null,
    val category: String? = null,
    val explanation: String? = null,
    val examples: List<String> = emptyList(),
    /** Checklist order within the level (0-based). */
    val position: Int,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
)

/** Used for both create (POST) and full replace (PUT). */
@Serializable
data class GrammarTopicRequest(
    val name: String,
    val level: LanguageLevel? = null,
    val category: String? = null,
    val explanation: String? = null,
    val examples: List<String> = emptyList(),
) {
    fun validate(): ValidationResult {
        val errors = buildList {
            if (name.isBlank()) add("Name can't be empty")
            if (category != null && category.length > 100) add("Category must be at most 100 characters")
        }
        return if (errors.isNotEmpty()) ValidationResult.Invalid(errors) else ValidationResult.Valid
    }
}

/** `PUT /grammar-topics/order`: every grammar topic of [level] (null = without level), in the new order. */
@Serializable
data class ReorderGrammarTopicsRequest(
    val level: LanguageLevel? = null,
    val ids: List<String>,
)

@Serializable
data class GrammarTopicRef(
    val id: String,
    val name: String,
    val level: LanguageLevel? = null,
)
