package com.gvart.parleyroom.common.transfer

import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ProblemDetail(
    val type: String = "about:blank",
    val title: String? = null,
    val status: Int? = null,
    val detail: String? = null,
    val instance: String? = null,
    val code: String? = null,
    /** JSON pointer of the offending value in the request body, when known. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val pointer: String? = null,
    /** The resource's current revision, on an optimistic-concurrency conflict. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val currentRevision: Int? = null,
    /** What is still tagged with a topic that cannot be deleted without `force`. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val usage: TagUsage? = null,
    /** The resource that already exists, on a one-per-X conflict (e.g. LESSON_PLAN_EXISTS: the plan id). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val existingId: String? = null,
    /** When a daily limit lifts again (e.g. PRACTICE_SENTENCE_LIMIT). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @Serializable(with = OffsetDateTimeSerializer::class)
    val resetsAt: OffsetDateTime? = null,
    /** Field-level failures (validation); each names the offending value by JSON pointer when known. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val errors: List<FieldError>? = null,
) {
    companion object {
        fun of(httpStatus: HttpStatusCode, detail: String? = null, code: String? = null, pointer: String? = null): ProblemDetail {
            return ProblemDetail(
                title = httpStatus.description,
                status = httpStatus.value,
                detail = detail,
                code = code,
                pointer = pointer,
                errors = pointer?.let { listOf(FieldError(it, code, detail)) },
            )
        }
    }
}

/** One entry of [ProblemDetail.errors]. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class FieldError(
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val pointer: String? = null,
    val code: String? = null,
    val detail: String? = null,
)
