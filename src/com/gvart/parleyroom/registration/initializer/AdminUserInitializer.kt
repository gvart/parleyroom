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
    val log = environment.log
    val adminEmail = environment.config.property("application.admin.email").getString()
    val adminPassword = environment.config.property("application.admin.default_password").getString()

    if (adminPassword == "admin") {
        log.warn("Admin default password is set to 'admin'. This is insecure and MUST be changed in production!")
    }

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
                    it[UserTable.role] = UserRole.ADMIN
                    it[UserTable.passwordHash] = BCrypt.hashpw(adminPassword, BCrypt.gensalt())
                }
                log.info("Admin user created with email: {}", adminEmail)
            }
        }
    }
}