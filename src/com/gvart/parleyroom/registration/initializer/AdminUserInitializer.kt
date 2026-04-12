package com.gvart.parleyroom.registration.initializer

import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserTable
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import java.time.OffsetDateTime

fun Application.initializeAdminUser() {
    val adminEmail = "admin@admin.co"
    monitor.subscribe(ApplicationStarted) {
        transaction {
            val exists = UserTable.selectAll()
                .where { UserTable.email eq adminEmail }
                .empty()
                .not()

            if (!exists) {
                UserTable.insert {
                    it[UserTable.email] = adminEmail
                    it[UserTable.firstName] = "admin"
                    it[UserTable.lastName] = "admin"
                    it[UserTable.initials] = "A"
                    it[UserTable.createdAt] = OffsetDateTime.now()
                    it[UserTable.updatedAt] = OffsetDateTime.now()
                    it[UserTable.role] = UserRole.ADMIN
                    it[UserTable.passwordHash] = BCrypt.hashpw(environment.config.property("application.admin.default_password").getString(), BCrypt.gensalt())
                }
            }
        }
    }
}