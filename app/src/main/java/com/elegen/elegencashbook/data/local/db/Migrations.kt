package com.elegen.elegencashbook.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 → v2: adds the outbox table only (P4). Existing tables are untouched, so no user data
 * moves — constitution §2 (no transaction lost). DDL copied verbatim from the exported
 * schema (app/schemas/.../2.json) so it matches what Room validates on open.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_queue` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`idempotencyKey` TEXT NOT NULL, `entityType` TEXT NOT NULL, `entityId` TEXT NOT NULL, " +
                "`operation` TEXT NOT NULL, `payloadVersion` INTEGER NOT NULL, `retryCount` INTEGER NOT NULL, " +
                "`maxRetry` INTEGER NOT NULL, `lastAttempt` INTEGER, `status` TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_queue_status_id` ON `sync_queue` (`status`, `id`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_queue_idempotencyKey` ON `sync_queue` (`idempotencyKey`)")
    }
}

/**
 * v2 → v3: backfill. The outbox only gets a row *at write time* (§6.3) — every business/book/
 * transaction created before this device ever ran outbox-aware code (P0–P3 builds) has no outbox
 * row and would otherwise never sync. One-time INSERT OR IGNORE per table queues all of it as a
 * CREATE (the push path only cares about current state, so the operation label is informational —
 * spec §6.3). Idempotent: reruns are no-ops via the unique idempotencyKey index; a fresh install
 * has empty entity tables, so this is a no-op there too.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT OR IGNORE INTO sync_queue
              (idempotencyKey, entityType, entityId, operation, payloadVersion, retryCount, maxRetry, lastAttempt, status)
            SELECT id || ':' || version, 'BUSINESS', id, 'CREATE', version, 0, 5, NULL, 'PENDING' FROM businesses
            """
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO sync_queue
              (idempotencyKey, entityType, entityId, operation, payloadVersion, retryCount, maxRetry, lastAttempt, status)
            SELECT id || ':' || version, 'BOOK', id, 'CREATE', version, 0, 5, NULL, 'PENDING' FROM books
            """
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO sync_queue
              (idempotencyKey, entityType, entityId, operation, payloadVersion, retryCount, maxRetry, lastAttempt, status)
            SELECT id || ':' || version, 'TRANSACTION', id, 'CREATE', version, 0, 5, NULL, 'PENDING' FROM transactions
            """
        )
    }
}

/** v3 → v4: adds the edit-history trail (append-only, no envelope — see [HistoryEntity]). */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `history_log` (" +
                "`id` TEXT NOT NULL PRIMARY KEY, `entityType` TEXT NOT NULL, `entityId` TEXT NOT NULL, " +
                "`bookId` TEXT NOT NULL, `action` TEXT NOT NULL, `changes` TEXT, `actorUid` TEXT NOT NULL, " +
                "`deviceId` TEXT NOT NULL, `at` INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_history_log_entityType_entityId_at` ON `history_log` (`entityType`, `entityId`, `at`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_history_log_bookId_at` ON `history_log` (`bookId`, `at`)")
    }
}
