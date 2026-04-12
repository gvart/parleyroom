package com.gvart.parleyroom.material.transfer

import kotlinx.serialization.Serializable

@Serializable
data class CreateMaterialResponse(
    val material: MaterialResponse,
    val uploadUrl: String? = null,
    val uploadContentType: String? = null,
)
