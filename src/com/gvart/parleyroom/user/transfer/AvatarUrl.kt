package com.gvart.parleyroom.user.transfer

import java.util.UUID

/** Client URL of a user's avatar ([key] = `users.avatar_url`, the storage key), cache-busted per upload; null without one. */
fun avatarUrl(userId: UUID, key: String?): String? =
    key?.let {
        val cacheBust = it.substringAfterLast('/')
        "/api/v1/users/$userId/avatar?v=$cacheBust"
    }
