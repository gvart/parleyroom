package com.gvart.parleyroom.ai.data

import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

/** What a model call is for; each one is routed to its own model (ai_model_settings). */
enum class AiUseCase { GENERATE, REFINE, FILL_TRANSLATIONS, SUGGEST_TAGS, SENTENCE_FEEDBACK, ROADMAP, MILESTONE_REGEN, LESSON_PREP }

object AiModelSettingsTable : Table("ai_model_settings") {
    val useCase = enumerationByName<AiUseCase>("use_case", 32)
    val model = varchar("model", 128)
    val updatedAt = timestampWithTimeZone("updated_at")
    val updatedBy = optReference("updated_by", UserTable, onDelete = ReferenceOption.SET_NULL)

    override val primaryKey = PrimaryKey(useCase)
}

object AiUsageTable : UUIDTable("ai_usage") {
    val userId = reference("user_id", UserTable, onDelete = ReferenceOption.CASCADE)
    val useCase = enumerationByName<AiUseCase>("use_case", 32)
    val model = varchar("model", 128)
    val jobId = optReference("job_id", GenerationJobTable, onDelete = ReferenceOption.SET_NULL)
    val success = bool("success")
    val errorCode = varchar("error_code", 64).nullable()
    val inputTokens = integer("input_tokens").default(0)
    val outputTokens = integer("output_tokens").default(0)
    val cacheReadTokens = integer("cache_read_tokens").default(0)
    val cacheWriteTokens = integer("cache_write_tokens").default(0)
    val costUsd = decimal("cost_usd", 12, 6)
    val createdAt = timestampWithTimeZone("created_at")
}
