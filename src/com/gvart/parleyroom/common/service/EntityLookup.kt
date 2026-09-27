package com.gvart.parleyroom.common.service

import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

fun Query.singleOrNotFound(entityName: String): ResultRow =
    singleOrNull() ?: throw NotFoundException(
        "$entityName not found",
        code = entityName.uppercase().replace(' ', '_') + "_NOT_FOUND",
    )

fun UUIDTable.findByIdOrThrow(entityId: UUID, entityName: String): ResultRow =
    selectAll().where { id eq entityId }.singleOrNotFound(entityName)
