package com.elegen.elegencashbook.data.repository

import com.elegen.elegencashbook.core.money.Money
import com.elegen.elegencashbook.data.local.dao.BookDao
import com.elegen.elegencashbook.data.local.dao.BusinessDao
import com.elegen.elegencashbook.data.local.dao.TransactionDao
import com.elegen.elegencashbook.data.local.entity.BookEntity
import com.elegen.elegencashbook.data.local.entity.BusinessEntity
import com.elegen.elegencashbook.data.local.entity.SyncEnvelope
import com.elegen.elegencashbook.data.local.entity.TransactionEntity
import com.elegen.elegencashbook.data.local.prefs.AppPreferences
import com.elegen.elegencashbook.data.mapper.toDomain
import com.elegen.elegencashbook.domain.model.Book
import com.elegen.elegencashbook.domain.model.BookWithBalance
import com.elegen.elegencashbook.domain.model.Business
import com.elegen.elegencashbook.domain.model.BusinessOverview
import com.elegen.elegencashbook.domain.model.EntryType
import com.elegen.elegencashbook.domain.model.Transaction
import com.elegen.elegencashbook.domain.repository.BookRepository
import com.elegen.elegencashbook.domain.repository.BusinessRepository
import com.elegen.elegencashbook.domain.repository.SettingsRepository
import com.elegen.elegencashbook.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-only repository implementations (spec §6.1: Room is the single source of truth;
 * repositories never talk to the network). Every write stamps the sync envelope so the
 * P4 outbox can pick changes up without schema changes.
 */

private const val DEFAULT_CURRENCY = "PKR"

/** Placeholder owner uid until auth (P3) provides a real one. */
private const val LOCAL_UID = "local"

private fun newEnvelope(deviceId: String, now: Long) = SyncEnvelope(
    version = 1,
    updatedAt = now,
    deviceId = deviceId,
    deletedAt = null,
    syncState = SyncEnvelope.STATE_PENDING,
)

private fun SyncEnvelope.bumped(deviceId: String, now: Long, deletedAt: Long? = this.deletedAt) = copy(
    version = version + 1,
    updatedAt = now,
    deviceId = deviceId,
    deletedAt = deletedAt,
    syncState = SyncEnvelope.STATE_PENDING,
)

@Singleton
class BusinessRepositoryImpl @Inject constructor(
    private val dao: BusinessDao,
    private val prefs: AppPreferences,
) : BusinessRepository {

    override fun observeBusinesses(): Flow<List<BusinessOverview>> =
        dao.observeWithBookCount().map { rows -> rows.map { it.toDomain() } }

    override suspend fun create(name: String): Business {
        val now = System.currentTimeMillis()
        val entity = BusinessEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            ownerUid = LOCAL_UID,
            currency = DEFAULT_CURRENCY,
            createdAt = now,
            sync = newEnvelope(prefs.deviceId(), now),
        )
        dao.upsert(entity)
        return entity.toDomain()
    }
}

@Singleton
class BookRepositoryImpl @Inject constructor(
    private val dao: BookDao,
    private val prefs: AppPreferences,
) : BookRepository {

    override fun observeBooksWithBalance(businessId: String): Flow<List<BookWithBalance>> =
        dao.observeWithBalance(businessId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun create(businessId: String, name: String): Book {
        val now = System.currentTimeMillis()
        val entity = BookEntity(
            id = UUID.randomUUID().toString(),
            businessId = businessId,
            ownerUid = LOCAL_UID,
            name = name,
            currency = DEFAULT_CURRENCY,
            createdAt = now,
            sync = newEnvelope(prefs.deviceId(), now),
        )
        dao.upsert(entity)
        return entity.toDomain()
    }
}

@Singleton
class TransactionRepositoryImpl @Inject constructor(
    private val dao: TransactionDao,
    private val prefs: AppPreferences,
) : TransactionRepository {

    override fun observeEntries(bookId: String): Flow<List<Transaction>> =
        dao.observeActiveByBook(bookId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun add(
        bookId: String,
        type: EntryType,
        amount: Money,
        description: String,
        createdAt: Long,
    ): Transaction {
        val now = System.currentTimeMillis()
        val entity = TransactionEntity(
            id = UUID.randomUUID().toString(),
            bookId = bookId,
            type = type.name,
            amountPaisa = amount.paisa,
            categoryId = null,
            description = description,
            createdAt = createdAt,
            createdByUid = LOCAL_UID,
            sync = newEnvelope(prefs.deviceId(), now),
        )
        dao.upsert(entity)
        return entity.toDomain()
    }

    override suspend fun update(transaction: Transaction) {
        val existing = dao.getById(transaction.id) ?: return
        val now = System.currentTimeMillis()
        dao.upsert(
            existing.copy(
                type = transaction.type.name,
                amountPaisa = transaction.amount.paisa,
                description = transaction.description,
                createdAt = transaction.createdAt,
                sync = existing.sync.bumped(prefs.deviceId(), now),
            )
        )
    }

    override suspend fun softDelete(id: String) {
        val existing = dao.getById(id) ?: return
        val now = System.currentTimeMillis()
        dao.upsert(existing.copy(sync = existing.sync.bumped(prefs.deviceId(), now, deletedAt = now)))
    }

    override suspend fun restore(id: String) {
        val existing = dao.getById(id) ?: return
        val now = System.currentTimeMillis()
        dao.upsert(existing.copy(sync = existing.sync.bumped(prefs.deviceId(), now, deletedAt = null)))
    }
}

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val prefs: AppPreferences,
) : SettingsRepository {
    override val activeBusinessId: Flow<String?> = prefs.activeBusinessId
    override suspend fun setActiveBusinessId(id: String) = prefs.setActiveBusinessId(id)
}
