package com.gvart.parleyroom.material.transfer

import java.io.InputStream

sealed interface CreateMaterialInput {
    val request: CreateMaterialRequest

    data class Link(override val request: CreateMaterialRequest) : CreateMaterialInput

    data class File(
        override val request: CreateMaterialRequest,
        val fileName: String,
        val contentType: String,
        val size: Long,
        val stream: InputStream,
    ) : CreateMaterialInput
}