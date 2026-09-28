package com.gvart.parleyroom.common.data

import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.sql.ResultSet
import java.util.UUID

/** Plain SQL with positional uuid parameters, for set-based statements Exposed's DSL can't express. Must run in a transaction. */
object Sql {
    private val uuidType = UUIDColumnType()

    fun update(sql: String, vararg args: UUID) {
        TransactionManager.current().exec(sql, args.map { uuidType to it })
    }

    fun long(sql: String, vararg args: UUID): Long =
        TransactionManager.current().exec(sql, args.map { uuidType to it }) { rs -> if (rs.next()) rs.getLong(1) else 0L } ?: 0L

    fun <T : Any> rows(sql: String, vararg args: UUID, read: (ResultSet) -> T): List<T> =
        TransactionManager.current().exec(sql, args.map { uuidType to it }) { rs ->
            buildList { while (rs.next()) add(read(rs)) }
        } ?: emptyList()
}
