package com.gvart.parleyroom.ai.service

import com.gvart.parleyroom.ai.config.AiConfig
import com.gvart.parleyroom.ai.data.GenerationJobKind
import com.gvart.parleyroom.ai.data.GenerationJobStatus
import com.gvart.parleyroom.ai.data.GenerationJobTable
import com.gvart.parleyroom.ai.llm.LlmException
import com.gvart.parleyroom.common.transfer.exception.TooManyRequestsException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.util.UUID

/** Failure of a job with a stable error code (stored in `generation_jobs.error_code`). */
class AiJobFailure(
    val code: String,
    message: String,
    val attempts: Int = 0,
    val usage: Usage = Usage(),
) : Exception(message)

data class Usage(val inputTokens: Int? = null, val outputTokens: Int? = null) {
    operator fun plus(other: Usage) = Usage(sum(inputTokens, other.inputTokens), sum(outputTokens, other.outputTokens))

    private fun sum(a: Int?, b: Int?) = if (a == null && b == null) null else (a ?: 0) + (b ?: 0)
}

/** What a finished job stores. */
data class JobSuccess(
    val result: JsonElement,
    val attempts: Int,
    val usage: Usage,
    val documentId: UUID? = null,
)

/**
 * Runs generation jobs in-process (single backend pod) on coroutines. Jobs are rows in
 * `generation_jobs`; clients poll them. A teacher may have [AiConfig.maxActiveJobsPerTeacher]
 * active jobs; at most [AiConfig.maxConcurrentJobs] run at once, the rest wait QUEUED.
 */
class GenerationJobRunner(private val config: AiConfig) {

    private val log = LoggerFactory.getLogger(GenerationJobRunner::class.java)
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)
    private val slots = Semaphore(config.maxConcurrentJobs)
    private val admission = Any()

    /**
     * Inserts a QUEUED job after checking the teacher's active-job limit (429 AI_RATE_LIMITED).
     * The check and insert are serialized so parallel requests cannot both slip through.
     */
    fun enqueue(
        teacherId: UUID,
        lessonId: UUID?,
        kind: GenerationJobKind,
        input: JsonElement,
        parentJobId: UUID? = null,
        documentId: UUID? = null,
        materialId: UUID? = null,
        modelId: String,
    ): UUID = synchronized(admission) {
        transaction {
            val active = GenerationJobTable.selectAll()
                .where {
                    (GenerationJobTable.teacherId eq teacherId) and
                            (GenerationJobTable.status inList listOf(GenerationJobStatus.QUEUED, GenerationJobStatus.RUNNING))
                }
                .count()
            if (active >= config.maxActiveJobsPerTeacher)
                throw TooManyRequestsException(
                    "At most ${config.maxActiveJobsPerTeacher} AI jobs can run at the same time",
                    code = "AI_RATE_LIMITED",
                )
            GenerationJobTable.insertAndGetId {
                it[GenerationJobTable.teacherId] = teacherId
                it[GenerationJobTable.lessonId] = lessonId
                it[GenerationJobTable.kind] = kind
                it[status] = GenerationJobStatus.QUEUED
                it[GenerationJobTable.parentJobId] = parentJobId
                it[GenerationJobTable.documentId] = documentId
                it[GenerationJobTable.materialId] = materialId
                it[GenerationJobTable.input] = input
                it[model] = modelId
                it[createdAt] = OffsetDateTime.now()
            }.value
        }
    }

    /** Starts [work] for a QUEUED job once a slot is free, and records its outcome. */
    fun launch(jobId: UUID, work: suspend () -> JobSuccess) {
        scope.launch {
            slots.withPermit {
                markRunning(jobId)
                val outcome = try {
                    withTimeout(config.jobTimeout) { Result.success(work()) }
                } catch (e: TimeoutCancellationException) {
                    Result.failure(AiJobFailure("AI_TIMEOUT", "The AI job took longer than ${config.jobTimeout}"))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
                outcome.fold(
                    onSuccess = { markSucceeded(jobId, it) },
                    onFailure = { markFailed(jobId, it) },
                )
            }
        }
    }

    /** Jobs left QUEUED/RUNNING by a previous process can never finish: fail them. */
    fun failInterruptedJobs(): Int = transaction {
        GenerationJobTable.update({
            GenerationJobTable.status inList listOf(GenerationJobStatus.QUEUED, GenerationJobStatus.RUNNING)
        }) {
            it[status] = GenerationJobStatus.FAILED
            it[errorCode] = "AI_INTERRUPTED"
            it[errorMessage] = "The server restarted while the job was running"
            it[finishedAt] = OffsetDateTime.now()
        }
    }

    fun shutdown() {
        supervisor.cancel()
    }

    private fun markRunning(jobId: UUID) = transaction {
        GenerationJobTable.update({ GenerationJobTable.id eq jobId }) {
            it[status] = GenerationJobStatus.RUNNING
            it[startedAt] = OffsetDateTime.now()
        }
    }

    private fun markSucceeded(jobId: UUID, success: JobSuccess) {
        transaction {
            GenerationJobTable.update({ GenerationJobTable.id eq jobId }) {
                it[status] = GenerationJobStatus.SUCCEEDED
                it[result] = success.result
                if (success.documentId != null) it[documentId] = success.documentId
                it[attempts] = success.attempts
                it[inputTokens] = success.usage.inputTokens
                it[outputTokens] = success.usage.outputTokens
                it[finishedAt] = OffsetDateTime.now()
            }
        }
        log.info("AI job {} succeeded: attempts={} inputTokens={} outputTokens={}",
            jobId, success.attempts, success.usage.inputTokens, success.usage.outputTokens)
    }

    private fun markFailed(jobId: UUID, cause: Throwable) {
        val failure = when (cause) {
            is AiJobFailure -> cause
            is LlmException -> AiJobFailure(cause.code, cause.message ?: "The AI provider request failed")
            else -> {
                log.error("AI job {} crashed", jobId, cause)
                AiJobFailure("INTERNAL_ERROR", "Unexpected error while running the job")
            }
        }
        transaction {
            GenerationJobTable.update({ GenerationJobTable.id eq jobId }) {
                it[status] = GenerationJobStatus.FAILED
                it[errorCode] = failure.code
                it[errorMessage] = failure.message
                it[attempts] = failure.attempts
                it[inputTokens] = failure.usage.inputTokens
                it[outputTokens] = failure.usage.outputTokens
                it[finishedAt] = OffsetDateTime.now()
            }
        }
        log.info("AI job {} failed: code={} attempts={} inputTokens={} outputTokens={}",
            jobId, failure.code, failure.attempts, failure.usage.inputTokens, failure.usage.outputTokens)
    }
}
