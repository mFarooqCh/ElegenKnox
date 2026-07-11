package com.elegen.elegencashbook.data.mapper

import com.elegen.elegencashbook.core.money.Money
import com.elegen.elegencashbook.data.local.dao.BookWithBalanceRow
import com.elegen.elegencashbook.data.local.dao.BusinessWithCountRow
import com.elegen.elegencashbook.data.local.entity.BookEntity
import com.elegen.elegencashbook.data.local.entity.BusinessEntity
import com.elegen.elegencashbook.data.local.entity.HistoryEntity
import com.elegen.elegencashbook.data.local.entity.TransactionEntity
import com.elegen.elegencashbook.domain.model.Book
import com.elegen.elegencashbook.domain.model.BookWithBalance
import com.elegen.elegencashbook.domain.model.Business
import com.elegen.elegencashbook.domain.model.BusinessOverview
import com.elegen.elegencashbook.domain.model.EntryType
import com.elegen.elegencashbook.domain.model.HistoryAction
import com.elegen.elegencashbook.domain.model.HistoryEntityType
import com.elegen.elegencashbook.domain.model.HistoryEntry
import com.elegen.elegencashbook.domain.model.Transaction

/** Entity ⇄ domain mappers (spec §4 rules 2–3). Envelope internals stay in data/. */

fun BusinessEntity.toDomain() = Business(
    id = id,
    name = name,
    currency = currency,
    createdAt = createdAt,
    updatedAt = sync.updatedAt,
)

fun BusinessWithCountRow.toDomain() = BusinessOverview(
    business = business.toDomain(),
    bookCount = bookCount,
)

fun BookEntity.toDomain() = Book(
    id = id,
    businessId = businessId,
    name = name,
    currency = currency,
    createdAt = createdAt,
    updatedAt = sync.updatedAt,
)

fun BookWithBalanceRow.toDomain() = BookWithBalance(
    book = book.toDomain(),
    totalIn = Money(totalInPaisa),
    totalOut = Money(totalOutPaisa),
    entryCount = entryCount,
    lastEntryAt = lastEntryAt,
)

fun TransactionEntity.toDomain() = Transaction(
    id = id,
    bookId = bookId,
    type = EntryType.valueOf(type),
    amount = Money(amountPaisa),
    description = description,
    createdAt = createdAt,
    updatedAt = sync.updatedAt,
)

fun HistoryEntity.toDomain() = HistoryEntry(
    id = id,
    entityType = HistoryEntityType.valueOf(entityType),
    entityId = entityId,
    bookId = bookId,
    action = HistoryAction.valueOf(action),
    changes = changes,
    actorUid = actorUid,
    at = at,
)
