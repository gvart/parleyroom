package com.gvart.parleyroom.user.security

import kotlin.time.Duration

data class JwtConfig(
    val secret: String,
    val issuer: String,
    val audience: String,
    val realm: String,
    val duration: Duration,
    val refreshDuration: Duration,
)
