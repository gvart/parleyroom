package com.gvart.parleyroom.admin.service

import com.gvart.parleyroom.admin.data.FeatureFlag
import com.gvart.parleyroom.admin.data.FeatureFlagTable
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.user.security.UserPrincipal
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.OffsetDateTime

class FeatureFlagService {

    /** Every known flag by key; off unless an admin enabled it. */
    fun flags(): Map<String, Boolean> = transaction {
        val stored = FeatureFlagTable.selectAll().associate { it[FeatureFlagTable.name] to it[FeatureFlagTable.enabled] }
        FeatureFlag.entries.associate { it.key to (stored[it.key] ?: false) }
    }

    fun isEnabled(flag: FeatureFlag): Boolean = flags().getValue(flag.key)

    /** Sets the given flags; flags left out keep their value. */
    fun update(request: Map<String, Boolean>, principal: UserPrincipal): Map<String, Boolean> {
        val known = FeatureFlag.entries.map { it.key }.toSet()
        request.keys.firstOrNull { it !in known }?.let {
            throw BadRequestException("Unknown feature flag: $it", "UNKNOWN_FEATURE_FLAG", "/$it")
        }
        transaction {
            request.forEach { (key, value) ->
                FeatureFlagTable.upsert {
                    it[name] = key
                    it[enabled] = value
                    it[updatedAt] = OffsetDateTime.now()
                    it[updatedBy] = principal.id
                }
            }
        }
        return flags()
    }
}
