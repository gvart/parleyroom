package com.gvart.parleyroom.video.config

import com.gvart.parleyroom.video.service.VideoTokenService
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import kotlin.time.Duration

fun Application.configureVideoModule() {
    val config = environment.config

    dependencies {
        provide {
            VideoConfig(
                url = config.property("livekit.url").getString(),
                apiKey = config.property("livekit.api_key").getString(),
                apiSecret = config.property("livekit.api_secret").getString(),
                tokenTtl = Duration.parse(config.property("livekit.token_ttl").getString()),
            )
        }

        provide(VideoTokenService::class)
    }
}
