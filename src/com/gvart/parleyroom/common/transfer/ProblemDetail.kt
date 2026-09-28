package com.gvart.parleyroom.common.transfer

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

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
) {
    companion object {
        fun of(httpStatus: HttpStatusCode, detail: String? = null, code: String? = null, pointer: String? = null): ProblemDetail {
            return ProblemDetail(
                title = httpStatus.description,
                status = httpStatus.value,
                detail = detail,
                code = code,
                pointer = pointer,
            )
        }
    }
}
