package com.gvart.parleyroom.ai.transfer

import com.gvart.parleyroom.ai.data.AiUseCase
import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import com.gvart.parleyroom.user.data.UserRole
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class AiUseCaseSetting(
    val useCase: AiUseCase,
    val model: String,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val updatedAt: OffsetDateTime,
)

/** A selectable model with its base prices (USD per million tokens). */
@Serializable
data class AiModelOption(val id: String, val inputPerMTok: Double, val outputPerMTok: Double)

@Serializable
data class AiSettingsResponse(val useCases: List<AiUseCaseSetting>, val models: List<AiModelOption>)

@Serializable
data class UpdateAiModelRequest(val model: String)

@Serializable
data class AiUsageTotals(
    val calls: Int,
    val failedCalls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val costUsd: Double,
)

@Serializable
data class AiUsageDay(val date: String, val calls: Int, val failedCalls: Int, val costUsd: Double, val inputTokens: Long, val outputTokens: Long)

@Serializable
data class AiUsageByUseCase(
    val useCase: AiUseCase,
    val calls: Int,
    val failedCalls: Int,
    val costUsd: Double,
    val inputTokens: Long,
    val outputTokens: Long,
)

@Serializable
data class AiUsageByModel(val model: String, val calls: Int, val costUsd: Double)

@Serializable
data class AiUsageByUser(
    val userId: String,
    val firstName: String,
    val lastName: String,
    val email: String,
    val role: UserRole,
    val calls: Int,
    val failedCalls: Int,
    val costUsd: Double,
    val inputTokens: Long,
    val outputTokens: Long,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val lastUsedAt: OffsetDateTime,
)

/** GET /api/v1/admin/ai/usage: [from] and [to] are inclusive UTC days; [byDay] has every day of the range. */
@Serializable
data class AiUsageResponse(
    val from: String,
    val to: String,
    val totals: AiUsageTotals,
    val byDay: List<AiUsageDay>,
    val byUseCase: List<AiUsageByUseCase>,
    val byModel: List<AiUsageByModel>,
    val byUser: List<AiUsageByUser>,
)
