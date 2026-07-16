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

/**
 * v4 → v5 (P6): local mirrors of Postgres `business_members` / `book_grants`. Pull-only tables —
 * see [BusinessMemberEntity] / [BookGrantEntity] — so no outbox backfill needed, unlike P4's
 * MIGRATION_2_3 (nothing local ever originates a row here; the first pull after upgrade populates
 * both from scratch).
 */
/**
 * v5 → v6 (P8): backfill, same reasoning as [MIGRATION_2_3]. `history_log` rows written before
 * this device ever ran history-sync-aware code (this session's builds, pre-audit_log-push) have no
 * outbox row and would otherwise never leave the device — real bug found on-device: owner and a
 * shared admin each only ever saw their OWN locally-made history entries (CREATED vs RENAMED),
 * neither synced to the other, because `logHistory()` didn't queue an outbox row until this
 * feature's client wiring landed. One-time INSERT OR IGNORE queues every existing local history
 * row as a CREATE (idempotencyKey `id:1`, matching [OutboxWriter]'s scheme for history rows —
 * version is always 1, history rows are never updated in place). Idempotent; no-op on a fresh
 * install (empty history_log).
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT OR IGNORE INTO sync_queue
              (idempotencyKey, entityType, entityId, operation, payloadVersion, retryCount, maxRetry, lastAttempt, status)
            SELECT id || ':1', 'HISTORY', id, 'CREATE', 1, 0, 5, NULL, 'PENDING' FROM history_log
            """
        )
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `business_members` (" +
                "`id` TEXT NOT NULL PRIMARY KEY, `businessId` TEXT NOT NULL, `userUid` TEXT NOT NULL, " +
                "`role` TEXT NOT NULL, `status` TEXT NOT NULL, `bookScoped` INTEGER NOT NULL, " +
                "`invitedByUid` TEXT, `joinedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_business_members_businessId` ON `business_members` (`businessId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_business_members_userUid` ON `business_members` (`userUid`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `book_grants` (" +
                "`id` TEXT NOT NULL PRIMARY KEY, `bookId` TEXT NOT NULL, `userUid` TEXT NOT NULL, " +
                "`access` TEXT NOT NULL, `permsOverride` TEXT, `grantedByUid` TEXT, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_grants_bookId` ON `book_grants` (`bookId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_grants_userUid` ON `book_grants` (`userUid`)")
    }
}
