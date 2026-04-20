package com.gvart.parleyroom.common.service

import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Query
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.selectAll
import java.util.UUID

fun Query.singleOrNotFound(entityName: String): ResultRow =
    singleOrNull() ?: throw NotFoundException("$entityName not found")

fun UUIDTable.findByIdOrThrow(entityId: UUID, entityName: String): ResultRow =
    selectAll().where { id eq entityId }.singleOrNotFound(entityName)
