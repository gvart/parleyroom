package com.gvart.parleyroom.ai.service

import com.gvart.parleyroom.ai.config.AiPricing
import com.gvart.parleyroom.ai.data.AiModelSettingsTable
import com.gvart.parleyroom.ai.data.AiUseCase
import com.gvart.parleyroom.ai.transfer.AiModelOption
import com.gvart.parleyroom.ai.transfer.AiSettingsResponse
import com.gvart.parleyroom.ai.transfer.AiUseCaseSetting
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.user.security.UserPrincipal
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.OffsetDateTime

/** Per-use-case model routing (ai_model_settings), read on every call; [fallbackModel] (AI_MODEL) when a row is missing. */
class AiModelSettingsService(private val pricing: AiPricing, private val fallbackModel: String) {

    fun modelFor(useCase: AiUseCase): String = transaction {
        AiModelSettingsTable.select(AiModelSettingsTable.model).where { AiModelSettingsTable.useCase eq useCase }
            .singleOrNull()?.get(AiModelSettingsTable.model)
    } ?: fallbackModel

    fun settings(): AiSettingsResponse = transaction {
        val rows = AiModelSettingsTable.selectAll().associateBy { it[AiModelSettingsTable.useCase] }
        AiSettingsResponse(
            useCases = AiUseCase.entries.map { useCase ->
                val row = rows[useCase]
                AiUseCaseSetting(
                    useCase = useCase,
                    model = row?.get(AiModelSettingsTable.model) ?: fallbackModel,
                    updatedAt = row?.get(AiModelSettingsTable.updatedAt) ?: OffsetDateTime.now(),
                )
            },
            models = pricing.models.map { (id, price) ->
                AiModelOption(id, price.base.input.toDouble(), price.base.output.toDouble())
            }.sortedBy { it.id },
        )
    }

    fun update(rawUseCase: String, model: String, principal: UserPrincipal): AiUseCaseSetting {
        val useCase = AiUseCase.entries.firstOrNull { it.name == rawUseCase }
            ?: throw BadRequestException("Unknown AI use case '$rawUseCase'", code = "unknown_use_case")
        if (model !in pricing.models)
            throw BadRequestException("Model '$model' has no configured price", code = "unknown_model", pointer = "/model")
        val now = OffsetDateTime.now()
        transaction {
            AiModelSettingsTable.upsert {
                it[AiModelSettingsTable.useCase] = useCase
                it[AiModelSettingsTable.model] = model
                it[updatedAt] = now
                it[updatedBy] = principal.id
            }
        }
        return AiUseCaseSetting(useCase, model, now)
    }
}
