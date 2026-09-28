package com.gvart.parleyroom.ai.service

import com.gvart.parleyroom.ai.config.AiRuntime
import com.gvart.parleyroom.ai.data.GenerationJobKind
import com.gvart.parleyroom.ai.llm.LlmGateway
import com.gvart.parleyroom.ai.transfer.JobInput
import com.gvart.parleyroom.ai.transfer.SuggestTagsResult
import com.gvart.parleyroom.ai.transfer.SuggestedGrammarTopic
import com.gvart.parleyroom.ai.transfer.SuggestedTopic
import com.gvart.parleyroom.ai.transfer.TextSource
import com.gvart.parleyroom.ai.transfer.TextSourceKind
import com.gvart.parleyroom.common.storage.StorageService
import com.gvart.parleyroom.common.transfer.exception.TooManyRequestsException
import com.gvart.parleyroom.material.data.MaterialTable
import com.gvart.parleyroom.material.data.MaterialType
import com.gvart.parleyroom.topic.data.GrammarTopicTable
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.security.UserPrincipal
import kotlinx.serialization.json.encodeToJsonElement
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

/**
 * AI tag suggestions for a material (brief §3). Sends only the material's name, text extracted from
 * its file and the library's names; the result is a suggestion the teacher applies herself.
 */
class SuggestTagsService(
    private val ai: AiRuntime,
    private val runner: GenerationJobRunner,
    private val context: LessonContextService,
    private val storage: StorageService,
) {

    /** Auto-start after an upload: never fails the upload; null when AI is off or the teacher is at the job limit. */
    fun tryStart(materialId: UUID, principal: UserPrincipal): String? {
        val gateway = ai.gateway ?: return null
        if (principal.role != UserRole.TEACHER) return null
        return try {
            enqueue(gateway, materialId, principal.id).toString()
        } catch (_: TooManyRequestsException) {
            null
        }
    }

    private fun enqueue(gateway: LlmGateway, materialId: UUID, teacherId: UUID): UUID {
        val input = GenerationJobs.json.encodeToJsonElement(JobInput())
        val jobId = runner.enqueue(teacherId, null, GenerationJobKind.SUGGEST_TAGS, input, materialId = materialId, modelId = gateway.modelId)
        runner.launch(jobId) { run(gateway, materialId, teacherId) }
        return jobId
    }

    private suspend fun run(gateway: LlmGateway, materialId: UUID, teacherId: UUID): JobSuccess {
        val material = transaction { MaterialTable.selectAll().where { MaterialTable.id eq materialId }.singleOrNull() }
            ?: throw AiJobFailure("MATERIAL_NOT_FOUND", "The material was deleted")
        val extracted = extract(material)
        val (topicPaths, grammar) = transaction {
            context.topicPaths(teacherId) to GrammarTopicTable.selectAll()
                .where { GrammarTopicTable.teacherId eq teacherId }
                .orderBy(GrammarTopicTable.level to SortOrder.ASC_NULLS_LAST, GrammarTopicTable.name to SortOrder.ASC)
                .limit(LessonContextService.MAX_LIBRARY_ITEMS)
                .map { row -> row[GrammarTopicTable.level]?.let { "${row[GrammarTopicTable.name]} ($it)" } ?: row[GrammarTopicTable.name] }
        }
        val request = Prompts.suggestTags(material[MaterialTable.name], extracted.kind.name, extracted.text, topicPaths, grammar)
        val completion = GenerationJobs.completeValidated(gateway, Prompts.suggestTagsSystem, request, MAX_TOKENS, AiOutputParser::parseSuggestTags)

        val output = completion.value
        val result = transaction {
            val matcher = LibraryMatcher(teacherId)
            SuggestTagsResult(
                level = output.level,
                skill = output.skill,
                topics = output.topics.map { SuggestedTopic(it.name, it.parentName, matcher.matchTopic(it.name, it.parentName)?.toString()) },
                grammarTopics = output.grammarTopics.map { SuggestedGrammarTopic(it.name, it.level, matcher.matchGrammar(it.name)?.toString()) },
                source = TextSource(extracted.kind, extracted.text.length, extracted.truncated),
            )
        }
        return JobSuccess(GenerationJobs.json.encodeToJsonElement(result), completion.attempts, completion.usage)
    }

    private fun extract(material: ResultRow): MaterialText.Extracted {
        val key = material[MaterialTable.url]
        val kind = if (material[MaterialTable.type] == MaterialType.PDF && key.isNotBlank())
            MaterialText.kindOf(material[MaterialTable.contentType], key.substringAfterLast('/'))
        else null
        if (kind == null) return MaterialText.Extracted(TextSourceKind.NAME_ONLY, "", false)
        return MaterialText.extract(kind, material[MaterialTable.fileSize]) { storage.stream(key) }
    }

    companion object {
        const val MAX_TOKENS = 2_000
    }
}
