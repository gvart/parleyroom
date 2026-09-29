package com.gvart.parleyroom.ai.service

import com.gvart.parleyroom.ai.data.GenerationJobStatus
import com.gvart.parleyroom.ai.data.GenerationJobTable
import com.gvart.parleyroom.ai.llm.LlmException
import com.gvart.parleyroom.ai.llm.LlmGateway
import com.gvart.parleyroom.ai.llm.LlmMessage
import com.gvart.parleyroom.ai.transfer.GenerationJobResponse
import com.gvart.parleyroom.ai.transfer.JobError
import com.gvart.parleyroom.ai.transfer.JobInput
import com.gvart.parleyroom.ai.transfer.JobUsage
import com.gvart.parleyroom.common.transfer.exception.ConflictException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.security.UserPrincipal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.slf4j.LoggerFactory
import java.util.UUID

/** Job lookup, rendering and the model call with one validation retry. */
object GenerationJobs {

    private val log = LoggerFactory.getLogger(GenerationJobs::class.java)

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The teacher's own job (admins may read any); others get 404 so ids cannot be probed. Must run in a transaction. */
    fun requireReadable(jobId: UUID, principal: UserPrincipal): ResultRow {
        val row = GenerationJobTable.selectAll().where { GenerationJobTable.id eq jobId }.singleOrNull()
        if (row == null || (principal.role != UserRole.ADMIN && row[GenerationJobTable.teacherId].value != principal.id))
            throw NotFoundException("AI job not found", code = "AI_JOB_NOT_FOUND")
        return row
    }

    /** Writes on a job are for its teacher only. */
    fun requireOwned(jobId: UUID, principal: UserPrincipal): ResultRow {
        val row = requireReadable(jobId, principal)
        if (row[GenerationJobTable.teacherId].value != principal.id)
            throw NotFoundException("AI job not found", code = "AI_JOB_NOT_FOUND")
        return row
    }

    fun requireSucceeded(row: ResultRow) {
        if (row[GenerationJobTable.status] != GenerationJobStatus.SUCCEEDED)
            throw ConflictException("The AI job has not succeeded", code = "AI_JOB_NOT_READY")
    }

    fun input(row: ResultRow): JobInput = json.decodeFromJsonElement(row[GenerationJobTable.input])

    fun toResponse(row: ResultRow): GenerationJobResponse {
        val usage = JobUsage(row[GenerationJobTable.inputTokens], row[GenerationJobTable.outputTokens])
        return GenerationJobResponse(
            id = row[GenerationJobTable.id].value.toString(),
            kind = row[GenerationJobTable.kind],
            status = row[GenerationJobTable.status],
            lessonId = row[GenerationJobTable.lessonId]?.value?.toString(),
            materialId = row[GenerationJobTable.materialId]?.value?.toString(),
            parentJobId = row[GenerationJobTable.parentJobId]?.value?.toString(),
            documentId = row[GenerationJobTable.documentId]?.value?.toString(),
            input = input(row),
            result = row[GenerationJobTable.result],
            error = row[GenerationJobTable.errorCode]?.let { JobError(it, row[GenerationJobTable.errorMessage].orEmpty()) },
            model = row[GenerationJobTable.model],
            attempts = row[GenerationJobTable.attempts],
            usage = usage.takeIf { it.inputTokens != null || it.outputTokens != null },
            createdAt = row[GenerationJobTable.createdAt],
            startedAt = row[GenerationJobTable.startedAt],
            finishedAt = row[GenerationJobTable.finishedAt],
            publishedAt = row[GenerationJobTable.publishedAt],
        )
    }

    data class Completion<T>(val value: T, val attempts: Int, val usage: Usage)

    /**
     * Calls the model and parses the answer; on invalid output retries once with the problems fed
     * back. Throws [AiJobFailure] (AI_OUTPUT_INVALID or the provider's code) otherwise.
     */
    suspend fun <T> completeValidated(
        gateway: LlmGateway,
        system: String,
        request: String,
        maxTokens: Int,
        parse: (String) -> T,
    ): Completion<T> {
        val messages = mutableListOf(LlmMessage.user(request))
        var usage = Usage()
        for (attempt in 1..2) {
            val reply = try {
                gateway.complete(system, messages, maxTokens)
            } catch (e: LlmException) {
                throw AiJobFailure(e.code, e.message ?: "The AI provider request failed", attempt, usage)
            }
            usage += Usage(reply.inputTokens, reply.outputTokens)
            // A retry would hit the same limit, so a cut-off answer fails right away.
            if (reply.truncated) {
                log.warn("AI output cut off at maxTokens={} (attempt {}, outputTokens={})", maxTokens, attempt, reply.outputTokens)
                throw AiJobFailure("AI_OUTPUT_INVALID", "The AI answer was cut off at the output limit", attempt, usage)
            }
            try {
                return Completion(parse(reply.text), attempt, usage)
            } catch (e: AiOutputInvalid) {
                // Issue messages can quote model text: they go to the log and the model, never to clients.
                log.info("AI output invalid (attempt {}): {}", attempt, e.issues.take(5).joinToString("; ") { "${it.pointer} ${it.message}" })
                if (attempt == 2)
                    throw AiJobFailure("AI_OUTPUT_INVALID", "The AI answer was still invalid after a retry (${e.issues.size} problems)", attempt, usage)
                // The provider rejects an empty assistant turn; without one the retry is two user turns.
                if (reply.text.isNotBlank()) messages += LlmMessage.assistant(reply.text)
                messages += LlmMessage.user(Prompts.retry(e.issues))
            }
        }
        error("unreachable")
    }
}
