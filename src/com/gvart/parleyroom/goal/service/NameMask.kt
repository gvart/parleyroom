package com.gvart.parleyroom.goal.service

import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import java.util.UUID

/** Replaces a student's first and last name by "[Name]" in teacher free text sent to the model. */
class NameMask private constructor(private val names: List<String>) {

    fun mask(text: String?): String? {
        if (text.isNullOrBlank()) return text
        return names.fold(text) { acc, name -> acc.replace(Regex("\\b${Regex.escape(name)}\\b", RegexOption.IGNORE_CASE), "[Name]") }
    }

    companion object {
        /** Must run in a transaction. */
        fun forStudent(studentId: UUID): NameMask {
            val user = UserTable.select(UserTable.firstName, UserTable.lastName).where { UserTable.id eq studentId }.singleOrNull()
            val names = user?.let { listOf(it[UserTable.firstName], it[UserTable.lastName]) }.orEmpty()
            return NameMask(names.map(String::trim).filter { it.length >= 2 })
        }
    }
}
