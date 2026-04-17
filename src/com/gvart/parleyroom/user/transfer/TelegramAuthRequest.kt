package com.gvart.parleyroom.user.transfer

import kotlinx.serialization.Serializable

@Serializable
data class TelegramAuthRequest(
    val initData: String,
)

@Serializable
data class TelegramLinkResult(
    val telegramId: Long,
    val telegramUsername: String?,
)
