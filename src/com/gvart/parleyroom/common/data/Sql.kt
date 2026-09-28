package com.gvart.parleyroom.common.data

import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.sql.ResultSet
import java.util.UUID

/**
 * Plain SQL with positional parameters (uuids and strings), for set-based statements Exposed's
 * DSL can't express (recursive CTEs, jsonb rewrites, grouped unions). Must run in a transaction.
 */
object Sql {
    private val uuidType = UUIDColumnType()
    private val textType = TextColumnType()

    fun update(sql: String, vararg args: Any) {
        TransactionManager.current().exec(sql, bind(args))
    }

    fun long(sql: String, vararg args: Any): Long =
        TransactionManager.current().exec(sql, bind(args), StatementType.SELECT) { rs -> if (rs.next()) rs.getLong(1) else 0L } ?: 0L

    fun <T : Any> rows(sql: String, vararg args: Any, read: (ResultSet) -> T): List<T> =
        TransactionManager.current().exec(sql, bind(args), StatementType.SELECT) { rs ->
            buildList { while (rs.next()) add(read(rs)) }
        } ?: emptyList()

    private fun bind(args: Array<out Any>): List<Pair<IColumnType<*>, Any>> = args.map { arg ->
        when (arg) {
            is UUID -> uuidType to arg
            is String -> textType to arg
            else -> throw IllegalArgumentException("Unsupported SQL parameter type ${arg::class}")
        }
    }
}
