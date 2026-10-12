package com.gvart.parleyroom.admin.data

import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

/** Admin-toggled feature flags; a flag without a row is off. */
object FeatureFlagTable : Table("feature_flags") {
    val name = text("name")
    val enabled = bool("enabled").default(false)
    val updatedAt = timestampWithTimeZone("updated_at")
    val updatedBy = optReference("updated_by", UserTable, onDelete = ReferenceOption.SET_NULL)

    override val primaryKey = PrimaryKey(name)
}
