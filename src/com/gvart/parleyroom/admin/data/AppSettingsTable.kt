package com.gvart.parleyroom.admin.data

import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

/** Admin-editable platform settings: a single row with id = 1. */
object AppSettingsTable : Table("app_settings") {
    val id = short("id").default(ROW_ID)
    val inviteExpiryDays = integer("invite_expiry_days").default(DEFAULT_INVITE_EXPIRY_DAYS)
    val updatedAt = timestampWithTimeZone("updated_at")
    val updatedBy = optReference("updated_by", UserTable, onDelete = ReferenceOption.SET_NULL)

    override val primaryKey = PrimaryKey(id)

    const val ROW_ID: Short = 1
    const val DEFAULT_INVITE_EXPIRY_DAYS = 7
}
