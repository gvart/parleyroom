package com.gvart.parleyroom.user.security

import kotlin.time.Duration

data class AuthLockoutConfig(
    val maxFailedAttempts: Int,
    val lockoutDuration: Duration,
)
