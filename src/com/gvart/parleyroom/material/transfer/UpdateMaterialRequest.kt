package com.gvart.parleyroom.material.transfer

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.material.data.MaterialSkill
import kotlinx.serialization.Serializable

/**
 * PATCH body: only non-null fields are applied. To clear a tag or move to root,
 * use the dedicated sub-resource endpoints (e.g. `DELETE /materials/{id}/folder`).
 */
@Serializable
data class UpdateMaterialRequest(
    val name: String? = null,
    val folderId: String? = null,
    val level: LanguageLevel? = null,
    val skill: MaterialSkill? = null,
)
