package com.elegen.elegencashbook.data.repository

import androidx.room.withTransaction
import com.elegen.elegencashbook.data.local.dao.SyncQueueDao
import com.elegen.elegencashbook.data.local.db.AppDatabase
import com.elegen.elegencashbook.data.local.entity.SyncQueueEntity
import com.elegen.elegencashbook.domain.repository.SyncScheduler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The write path's outbox stamp (spec §6.3): every local mutation and its outbox row commit in one
 * Room transaction, so a change can never be persisted without also being queued for the cloud
 * (constitution §2 — no transaction lost). After the commit it kicks the drain worker.
 */
@Singleton
class OutboxWriter @Inject constructor(
    private val db: AppDatabase,
    private val outbox: SyncQueueDao,
    private val scheduler: SyncScheduler,
) {
    /** Single-entity write: [block] performs the entity upsert; the outbox row is queued atomically. */
    suspend fun <T> write(
        entityType: String,
        entityId: String,
        version: Long,
        operation: String,
        block: suspend () -> T,
    ): T {
        val result = db.withTransaction {
            val r = block()
            enqueue(entityType, entityId, version, operation)
            r
        }
        scheduler.requestPush()
        return result
    }

    /** Queue one outbox row. MUST be called inside an existing [db] transaction with its entity write. */
    suspend fun enqueue(entityType: String, entityId: String, version: Long, operation: String) {
        outbox.insert(
            SyncQueueEntity(
                idempotencyKey = "$entityId:$version",
                entityType = entityType,
                entityId = entityId,
                operation = operation,
                payloadVersion = version,
            )
        )
    }

    fun requestPush() = scheduler.requestPush()
}
