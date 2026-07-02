package com.elegen.elegencashbook.data.local.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entities (spec §7). Local source of truth. Every entity carries the sync envelope;
 * filled locally in P2, consumed by the outbox/pull workers from P4 on.
 * Entities never leave data/ — mappers convert to domain models (spec §4 rules 2–3).
 */

/** Sync envelope columns, embedded in every synced entity (spec §7). */
data class SyncEnvelope(
    val version: Long,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long?,
    /** PENDING = not yet pushed; SYNCED = acknowledged by server (P4+). */
    val syncState: String,
) {
    companion object {
        const val STATE_PENDING = "PENDING"
        const val STATE_SYNCED = "SYNCED"
    }
}

@Entity(tableName = "businesses")
data class BusinessEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** Local placeholder until auth (P3) assigns a real uid. */
    val ownerUid: String,
    val currency: String,
    val createdAt: Long,
    @Embedded val sync: SyncEnvelope,
)

@Entity(
    tableName = "books",
    indices = [Index("businessId")],
)
data class BookEntity(
    @PrimaryKey val id: String,
    /** null = personal book (spec §7). */
    val businessId: String?,
    val ownerUid: String,
    val name: String,
    val currency: String,
    val createdAt: Long,
    @Embedded val sync: SyncEnvelope,
)

@Entity(
    tableName = "transactions",
    indices = [Index("bookId", "createdAt"), Index("bookId", "type")],
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    /** CASH_IN | CASH_OUT (EntryType name). */
    val type: String,
    val amountPaisa: Long,
    val categoryId: String?,
    val description: String,
    val createdAt: Long,
    val createdByUid: String,
    @Embedded val sync: SyncEnvelope,
)

/** Minimal placeholder (spec §7); no UI/DAO until reports/categories land. */
@Entity(
    tableName = "categories",
    indices = [Index("bookId")],
)
data class CategoryEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val name: String,
    val type: String,
    @Embedded val sync: SyncEnvelope,
)
