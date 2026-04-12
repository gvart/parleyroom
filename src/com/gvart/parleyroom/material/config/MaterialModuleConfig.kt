package com.gvart.parleyroom.material.config

import com.gvart.parleyroom.common.storage.StorageConfig
import com.gvart.parleyroom.common.storage.StorageService
import com.gvart.parleyroom.material.routing.configureMaterialRouting
import com.gvart.parleyroom.material.service.MaterialService
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import kotlin.time.Duration

fun Application.configureMaterialModule() {
    val config = environment.config

    dependencies {
        provide {
            StorageConfig(
                endpoint = config.property("storage.endpoint").getString(),
                region = config.property("storage.region").getString(),
                accessKey = config.property("storage.access_key").getString(),
                secretKey = config.property("storage.secret_key").getString(),
                bucket = config.property("storage.bucket").getString(),
                uploadUrlTtl = Duration.parse(config.property("storage.upload_url_ttl").getString()),
                downloadUrlTtl = Duration.parse(config.property("storage.download_url_ttl").getString()),
                pathStyleAccess = config.property("storage.path_style_access").getString().toBoolean(),
            )
        }
        provide(StorageService::class)
        provide(MaterialService::class)
    }

    configureMaterialRouting()
}
