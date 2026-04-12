package com.gvart.parleyroom.common.data

import org.jetbrains.exposed.sql.Table
import org.postgresql.util.PGobject

inline fun <reified T : Enum<T>> Table.pgEnum(columnName: String, pgTypeName: String) =
    customEnumeration(
        name = columnName,
        sql = pgTypeName,
        fromDb = { value -> enumValueOf<T>((value as String).uppercase()) },
        toDb = { PGobject().apply { type = pgTypeName; value = it.name.uppercase() } }
    )