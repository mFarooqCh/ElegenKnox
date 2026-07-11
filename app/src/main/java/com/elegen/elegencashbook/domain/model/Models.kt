package com.elegen.elegencashbook.domain.model

import com.elegen.elegencashbook.core.money.Money

/** Domain models (spec §7). Pure Kotlin — no Room, no Supabase, no Android (spec §4 rule 1). */

data class Business(
    val id: String,
    val name: String,
    val currency: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class BusinessOverview(
    val business: Business,
    val bookCount: Int,
)

data class Book(
    val id: String,
    /** null = personal book (spec §7); P2 books always belong to a business. */
    val businessId: String?,
    val name: String,
    val currency: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class BookWithBalance(
    val book: Book,
    val totalIn: Money,
    val totalOut: Money,
    val entryCount: Int,
    val lastEntryAt: Long?,
) {
    val net: Money get() = totalIn - totalOut
}

enum class EntryType { CASH_IN, CASH_OUT }

data class Transaction(
    val id: String,
    val bookId: String,
    val type: EntryType,
    val amount: Money,
    val description: String,
    /** User-facing entry date/time (picker-set), also the chronological sort key (spec §7 index). */
    val createdAt: Long,
    val updatedAt: Long,
)

data class BalanceSummary(
    val totalIn: Money,
    val totalOut: Money,
) {
    val net: Money get() = totalIn - totalOut
}

enum class HistoryEntityType { BOOK, TRANSACTION }

enum class HistoryAction { CREATED, UPDATED, RENAMED, MOVED, COPIED, DELETED, RESTORED, CONFLICT_OVERWRITTEN }

data class HistoryEntry(
    val id: String,
    val entityType: HistoryEntityType,
    val entityId: String,
    val bookId: String,
    val action: HistoryAction,
    /** "field=old→new;field2=old2→new2", null for CREATED/DELETED/RESTORED. */
    val changes: String?,
    val actorUid: String,
    val at: Long,
)
