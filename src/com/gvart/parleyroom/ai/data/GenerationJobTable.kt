package com.gvart.parleyroom.ai.data

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.data.pgEnum
import com.gvart.parleyroom.document.data.DocumentTable
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.material.data.MaterialTable
import com.gvart.parleyroom.user.data.UserTable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

enum class GenerationJobKind { GENERATE, REFINE, FILL_TRANSLATIONS, SUGGEST_TAGS }
enum class GenerationJobStatus { QUEUED, RUNNING, SUCCEEDED, FAILED }
enum class PromptTemplateLessonType { ONE_ON_ONE, CLUB }

object GenerationJobTable : UUIDTable("generation_jobs") {
    val teacherId = reference("teacher_id", UserTable)
    val lessonId = reference("lesson_id", LessonTable).nullable()
    val kind = pgEnum<GenerationJobKind>("kind", "GENERATION_JOB_KIND")
    val status = pgEnum<GenerationJobStatus>("status", "GENERATION_JOB_STATUS")
    val parentJobId = reference("parent_job_id", GenerationJobTable).nullable()
    val documentId = reference("document_id", DocumentTable).nullable()
    val materialId = reference("material_id", MaterialTable).nullable()
    val input = jsonb<JsonElement>("input", Json.Default)
    val result = jsonb<JsonElement>("result", Json.Default).nullable()
    val errorCode = varchar("error_code", 64).nullable()
    val errorMessage = text("error_message").nullable()
    val model = varchar("model", 128).nullable()
    val attempts = integer("attempts").default(0)
    val inputTokens = integer("input_tokens").nullable()
    val outputTokens = integer("output_tokens").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val startedAt = timestampWithTimeZone("started_at").nullable()
    val finishedAt = timestampWithTimeZone("finished_at").nullable()
    val publishedAt = timestampWithTimeZone("published_at").nullable()
    val bundleId = reference("bundle_id", DraftBundleTable).nullable()
}

object PromptTemplateTable : UUIDTable("prompt_templates") {
    val teacherId = reference("teacher_id", UserTable)
    val name = varchar("name", 100)
    val text = text("text")
    val level = pgEnum<LanguageLevel>("level", "LANGUAGE_LEVEL").nullable()
    val lessonType = pgEnum<PromptTemplateLessonType>("lesson_type", "PROMPT_TEMPLATE_LESSON_TYPE").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
}
