package com.gvart.parleyroom.material.data

import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

object MaterialShareTable : Table("material_shares") {
    val materialId = reference("material_id", MaterialTable)
    val studentId = reference("student_id", UserTable)
    val sharedBy = reference("shared_by", UserTable)
    val sharedAt = timestampWithTimeZone("shared_at")

    override val primaryKey = PrimaryKey(materialId, studentId)
}
