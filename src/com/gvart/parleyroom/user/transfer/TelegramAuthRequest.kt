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

@Serializable
data class TelegramLoginWidgetRequest(
    val id: Long,
    val firstName: String,
    val lastName: String? = null,
    val username: String? = null,
    val photoUrl: String? = null,
    val authDate: Long,
    val hash: String,
)
