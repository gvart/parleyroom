package com.gvart.parleyroom.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.events.EventDefinition
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database

fun Application.configureDatabase(existingDataSource: javax.sql.DataSource? = null): Database {
    val dataSource = existingDataSource ?: HikariDataSource(HikariConfig().apply {
        val dbConfig = environment.config.config("database")
        jdbcUrl = dbConfig.property("url").getString()
        username = dbConfig.property("user").getString()
        password = dbConfig.property("password").getString()
        maximumPoolSize = 5
        initializationFailTimeout = 30000
    }).also { hikari ->
        monitor.subscribe(ApplicationStopped) { hikari.close() }

        Flyway.configure()
            .dataSource(hikari)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }

    return Database.connect(dataSource)
}