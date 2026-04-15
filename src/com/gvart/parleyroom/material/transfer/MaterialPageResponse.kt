package com.gvart.parleyroom.material.transfer

import kotlinx.serialization.Serializable

@Serializable
data class MaterialPageResponse(
    val materials: List<MaterialResponse>,
    val total: Long,
    val page: Int,
    val pageSize: Int,
)
