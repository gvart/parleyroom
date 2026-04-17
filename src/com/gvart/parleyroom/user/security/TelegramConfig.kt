package com.gvart.parleyroom.user.security

import kotlin.time.Duration

data class TelegramConfig(
    val botToken: String,
    val initDataMaxAge: Duration,
)
