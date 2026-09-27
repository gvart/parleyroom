package com.gvart.parleyroom.group.transfer

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import com.gvart.parleyroom.group.data.GroupType
import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class GroupResponse(
    val id: String,
    val teacherId: String,
    val name: String,
    val level: LanguageLevel? = null,
    val type: GroupType,
    val members: List<GroupMemberResponse> = emptyList(),
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
)

@Serializable
data class GroupMemberResponse(
    val id: String,
    val firstName: String,
    val lastName: String,
    val level: LanguageLevel? = null,
)

/** Used for both create (POST, with optional initial members) and replace (PUT, members ignored). */
@Serializable
data class GroupRequest(
    val name: String,
    val level: LanguageLevel? = null,
    val type: GroupType,
    val studentIds: List<String> = emptyList(),
) {
    fun validate(): ValidationResult =
        if (name.isBlank()) ValidationResult.Invalid("Name can't be empty") else ValidationResult.Valid
}

@Serializable
data class GroupMembersRequest(
    val studentIds: List<String>,
)
