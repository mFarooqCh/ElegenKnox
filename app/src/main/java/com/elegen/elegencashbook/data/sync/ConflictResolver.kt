package com.elegen.elegencashbook.data.sync

/**
 * Last-Write-Wins conflict resolution (spec §6.6, [DECIDED]). Pure function of the two rows'
 * envelope fields — no Room/Supabase types — so it's table-driven-testable without a DB.
 */
object ConflictResolver {
    enum class Winner { LOCAL, REMOTE }

    /**
     * Winner = higher `updatedAt`. Tie on timestamp → higher `deviceId` wins (deterministic, so
     * all devices converge on the same winner independently). No local row at all → remote wins
     * trivially (first sight of an entity created elsewhere).
     */
    fun resolve(
        localUpdatedAt: Long?,
        localDeviceId: String?,
        remoteUpdatedAt: Long,
        remoteDeviceId: String?,
    ): Winner {
        if (localUpdatedAt == null) return Winner.REMOTE
        return when {
            remoteUpdatedAt > localUpdatedAt -> Winner.REMOTE
            remoteUpdatedAt < localUpdatedAt -> Winner.LOCAL
            (remoteDeviceId ?: "") > (localDeviceId ?: "") -> Winner.REMOTE
            else -> Winner.LOCAL
        }
    }
}
