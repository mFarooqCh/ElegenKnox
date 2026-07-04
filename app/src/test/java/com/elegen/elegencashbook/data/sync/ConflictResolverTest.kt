package com.elegen.elegencashbook.data.sync

import com.elegen.elegencashbook.data.sync.ConflictResolver.Winner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Table-driven per spec §13: LWW winner, timestamp tie → deviceId, tombstone vs update. */
class ConflictResolverTest {

    @Test
    fun `no local row - remote always wins (first sight of an entity created elsewhere)`() {
        assertEquals(
            Winner.REMOTE,
            ConflictResolver.resolve(localUpdatedAt = null, localDeviceId = null, remoteUpdatedAt = 100L, remoteDeviceId = "dev-b"),
        )
    }

    @Test
    fun `higher updatedAt wins - remote newer`() {
        assertEquals(
            Winner.REMOTE,
            ConflictResolver.resolve(localUpdatedAt = 100L, localDeviceId = "dev-a", remoteUpdatedAt = 200L, remoteDeviceId = "dev-b"),
        )
    }

    @Test
    fun `higher updatedAt wins - local newer`() {
        assertEquals(
            Winner.LOCAL,
            ConflictResolver.resolve(localUpdatedAt = 200L, localDeviceId = "dev-a", remoteUpdatedAt = 100L, remoteDeviceId = "dev-b"),
        )
    }

    @Test
    fun `timestamp tie - higher deviceId wins (deterministic across devices)`() {
        assertEquals(
            Winner.REMOTE,
            ConflictResolver.resolve(localUpdatedAt = 100L, localDeviceId = "dev-a", remoteUpdatedAt = 100L, remoteDeviceId = "dev-b"),
        )
        assertEquals(
            Winner.LOCAL,
            ConflictResolver.resolve(localUpdatedAt = 100L, localDeviceId = "dev-b", remoteUpdatedAt = 100L, remoteDeviceId = "dev-a"),
        )
    }

    @Test
    fun `timestamp and deviceId both tie - resolves to local (idempotent, no-op re-apply)`() {
        assertEquals(
            Winner.LOCAL,
            ConflictResolver.resolve(localUpdatedAt = 100L, localDeviceId = "dev-a", remoteUpdatedAt = 100L, remoteDeviceId = "dev-a"),
        )
    }

    @Test
    fun `tombstone (remote delete) beats an older local update - same LWW rule, no special case`() {
        // A remote tombstone is just a row with deletedAt set; the resolver only ever compares
        // envelope timestamps, so a delete-vs-update conflict resolves exactly like any other.
        assertEquals(
            Winner.REMOTE,
            ConflictResolver.resolve(localUpdatedAt = 100L, localDeviceId = "dev-a", remoteUpdatedAt = 150L, remoteDeviceId = "dev-b"),
        )
    }

    @Test
    fun `local update beats an older remote tombstone`() {
        assertEquals(
            Winner.LOCAL,
            ConflictResolver.resolve(localUpdatedAt = 150L, localDeviceId = "dev-a", remoteUpdatedAt = 100L, remoteDeviceId = "dev-b"),
        )
    }
}
