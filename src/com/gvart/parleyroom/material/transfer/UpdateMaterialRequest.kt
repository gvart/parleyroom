package com.gvart.parleyroom.material.transfer

import kotlinx.serialization.Serializable

@Serializable
data class UpdateMaterialRequest(
    val name: String? = null,
)
