package com.gvart.parleyroom.reminder

import com.gvart.parleyroom.admin.data.FeatureFlagTable
import com.gvart.parleyroom.ai.TEACHER
import com.gvart.parleyroom.common.data.Sql
import com.gvart.parleyroom.notification.data.NotificationTable
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.OffsetDateTime
import java.util.UUID

object TeacherReminderFixtures {

    /** Feature flags by key; after startApplication (its TRUNCATE users CASCADE turns them off again). */
    fun flags(vararg keys: String) = transaction {
        keys.forEach { key ->
            FeatureFlagTable.upsert {
                it[name] = key
                it[enabled] = true
                it[updatedAt] = OffsetDateTime.now()
            }
        }
    }

    fun notifications(userId: UUID = TEACHER): List<ResultRow> = transaction {
        NotificationTable.selectAll().where { NotificationTable.userId eq userId }
            .orderBy(NotificationTable.createdAt, SortOrder.ASC).toList()
    }

    fun setUser(userId: UUID, timezone: String? = null, telegramId: Long? = null, locale: String? = null) = transaction {
        UserTable.update({ UserTable.id eq userId }) {
            if (timezone != null) it[UserTable.timezone] = timezone
            if (telegramId != null) it[UserTable.telegramId] = telegramId
            if (locale != null) it[UserTable.locale] = locale
        }
    }

    /** A new student (first name [firstName]) actively linked to [teacher]. */
    fun linkedStudent(firstName: String, teacher: UUID = TEACHER): UUID = transaction {
        val id = UUID.randomUUID()
        val now = OffsetDateTime.now()
        UserTable.insert {
            it[UserTable.id] = id
            it[email] = "${firstName.lowercase()}-$id@test.com"
            it[UserTable.firstName] = firstName
            it[lastName] = "Muster"
            it[passwordHash] = "x"
            it[role] = UserRole.STUDENT
            it[initials] = firstName.take(1)
            it[createdAt] = now
            it[updatedAt] = now
        }
        Sql.update(
            "INSERT INTO teacher_students (teacher_id, student_id, lesson_types, status, started_at) " +
                "VALUES (?, ?, '{ONE_ON_ONE}', 'ACTIVE', now())",
            teacher, id,
        )
        id
    }
}
