package com.gvart.parleyroom.common.transfer

import kotlinx.serialization.Serializable

/** How many library items carry a topic / grammar topic tag. */
@Serializable
data class TagUsage(
    val words: Long,
    val documents: Long,
    val materials: Long,
    val lessons: Long,
) {
    val total: Long get() = words + documents + materials + lessons
}
