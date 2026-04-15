package com.gvart.parleyroom

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import com.gvart.parleyroom.user.transfer.AuthenticateRequest
import com.gvart.parleyroom.user.transfer.AuthenticateResponse
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.HostPortWaitStrategy
import org.testcontainers.containers.wait.strategy.Wait

abstract class IntegrationTest {

    companion object {
        @JvmField
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("parleyroom_test")
            .withUsername("test")
            .withPassword("test")
            .waitingFor(HostPortWaitStrategy())

        @JvmField
        val minio: GenericContainer<*> = GenericContainer("minio/minio:RELEASE.2024-12-18T13-15-44Z")
            .withExposedPorts(9000)
            .withCommand("server", "/data")
            .withEnv("MINIO_ROOT_USER", "minioadmin")
            .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000))

        val minioEndpoint: String
            get() = "http://${minio.host}:${minio.getMappedPort(9000)}"

        val sharedDataSource: HikariDataSource

        const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
        const val TEACHER_ID = "00000000-0000-0000-0000-000000000002"
        const val STUDENT_ID = "00000000-0000-0000-0000-000000000003"
        const val STUDENT_2_ID = "00000000-0000-0000-0000-000000000004"
        const val TEST_PASSWORD = "password123"

        init {
            postgres.start()
            minio.start()

            sharedDataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 5
                initializationFailTimeout = 30000
            })

            Flyway.configure()
                .dataSource(sharedDataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate()

            Database.connect(sharedDataSource)
        }

        private val testDataSql: String by lazy {
            IntegrationTest::class.java.classLoader.getResource("test-data.sql")!!.readText()
        }
    }

    protected fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment {
            config = MapApplicationConfig(
                "ktor.deployment.port" to "8080",
                "database.url" to postgres.jdbcUrl,
                "database.user" to postgres.username,
                "database.password" to postgres.password,
                "jwt.secret" to "test-secret",
                "jwt.issuer" to "parley-room",
                "jwt.audience" to "parley-room-api",
                "jwt.realm" to "TestRealm",
                "jwt.duration" to "60m",
                "jwt.refresh_duration" to "30d",
                "authentication.lockout.max_failed_attempts" to "5",
                "authentication.lockout.duration" to "15m",
                "livekit.url" to "ws://localhost:7880",
                "livekit.api_key" to "devkey",
                "livekit.api_secret" to "devsecretdevsecretdevsecretdevsecret",
                "livekit.token_ttl" to "2h",
                "application.admin.email" to "admin@admin.co",
                "application.admin.default_password" to "admin",
                "application.openapi.enabled" to "false",
                "vocabulary.review_reminder.enabled" to "false",
                "vocabulary.review_reminder.interval" to "1h",
                "storage.endpoint" to minioEndpoint,
                "storage.region" to "us-east-1",
                "storage.access_key" to "minioadmin",
                "storage.secret_key" to "minioadmin",
                "storage.bucket" to "parleyroom-test",
                "storage.download_url_ttl" to "1h",
                "storage.max_file_size" to "104857600",
                "storage.path_style_access" to "true",
            )
        }
        application {
            module(sharedDataSource)
            loadTestData()
        }
        block()
    }

    private fun loadTestData() {
        transaction {
            exec("TRUNCATE refresh_tokens, learning_goals, homework, vocabulary_words, lesson_events, lesson_students, lessons, password_resets, registrations, teacher_students, materials, users CASCADE")
            exec(testDataSql)
        }
    }

    protected fun createJsonClient(builder: ApplicationTestBuilder): HttpClient = builder.createClient {
        install(ContentNegotiation) {
            json()
        }
    }

    protected suspend fun getToken(client: HttpClient, email: String, password: String = TEST_PASSWORD): String {
        val response = client.post("/api/v1/token") {
            contentType(ContentType.Application.Json)
            setBody(AuthenticateRequest(email, password))
        }
        return response.body<AuthenticateResponse>().accessToken
    }

    protected suspend fun getAdminToken(client: HttpClient): String =
        getToken(client, "admin@test.com")

    protected suspend fun getTeacherToken(client: HttpClient): String =
        getToken(client, "teacher@test.com")

    protected suspend fun getStudentToken(client: HttpClient): String =
        getToken(client, "student@test.com")

    protected suspend fun getStudent2Token(client: HttpClient): String =
        getToken(client, "student2@test.com")
}
