package com.gvart.parleyroom.material.config

import com.gvart.parleyroom.common.storage.StorageConfig
import com.gvart.parleyroom.common.storage.StorageService
import com.gvart.parleyroom.material.routing.configureMaterialRouting
import com.gvart.parleyroom.material.service.MaterialService
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.plugins.di.dependencies

fun Application.configureMaterialModule() {
    val config = environment.config

    val storageConfig = StorageConfig(
        endpoint = config.property("storage.endpoint").getString(),
        region = config.property("storage.region").getString(),
        accessKey = config.property("storage.access_key").getString(),
        secretKey = config.property("storage.secret_key").getString(),
        bucket = config.property("storage.bucket").getString(),
        maxFileSize = config.property("storage.max_file_size").getString().toLong(),
        pathStyleAccess = config.property("storage.path_style_access").getString().toBoolean(),
    )

    val storageService = StorageService(storageConfig)

    dependencies {
        provide { storageConfig }
        provide { storageService }
        provide(MaterialService::class)
    }

    monitor.subscribe(ApplicationStopped) { storageService.close() }

    configureMaterialRouting()
}
