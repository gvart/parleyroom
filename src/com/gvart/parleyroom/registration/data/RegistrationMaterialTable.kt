package com.gvart.parleyroom.registration.data

import com.gvart.parleyroom.material.data.MaterialTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

/** Materials a teacher attached to a student invite; shared with the student on signup. */
object RegistrationMaterialTable : Table("registration_materials") {
    val registrationId = reference("registration_id", RegistrationTable)
    val materialId = reference("material_id", MaterialTable)
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(registrationId, materialId)
}
