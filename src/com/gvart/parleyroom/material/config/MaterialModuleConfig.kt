package com.gvart.parleyroom.material.config

import com.gvart.parleyroom.common.storage.StorageConfig
import com.gvart.parleyroom.common.storage.StorageService
import com.gvart.parleyroom.material.routing.configureMaterialFolderRouting
import com.gvart.parleyroom.material.routing.configureMaterialRouting
import com.gvart.parleyroom.material.service.LessonMaterialService
import com.gvart.parleyroom.material.service.MaterialAccessResolver
import com.gvart.parleyroom.material.service.MaterialFolderService
import com.gvart.parleyroom.material.service.MaterialService
import com.gvart.parleyroom.material.service.MaterialShareService
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
    val accessResolver = MaterialAccessResolver()

    dependencies {
        provide { storageConfig }
        provide { storageService }
        provide { accessResolver }
        provide(MaterialService::class)
        provide(MaterialFolderService::class)
        provide(MaterialShareService::class)
        provide(LessonMaterialService::class)
    }

    monitor.subscribe(ApplicationStopped) { storageService.close() }

    configureMaterialRouting()
    configureMaterialFolderRouting()
}
