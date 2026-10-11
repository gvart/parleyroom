package com.gvart.parleyroom.email.transfer

import kotlinx.serialization.Serializable

@Serializable
data class UnsubscribeRequest(val token: String)

@Serializable
data class UnsubscribeResponse(val category: EmailCategory)
