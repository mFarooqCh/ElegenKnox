package com.elegen.elegencashbook.data.repository

import com.elegen.elegencashbook.core.money.Money
import com.elegen.elegencashbook.data.local.dao.BookDao
import com.elegen.elegencashbook.data.local.dao.BusinessDao
import com.elegen.elegencashbook.data.local.dao.TransactionDao
import com.elegen.elegencashbook.data.local.entity.BookEntity
import com.elegen.elegencashbook.data.local.entity.BusinessEntity
import com.elegen.elegencashbook.data.local.entity.SyncEnvelope
import com.elegen.elegencashbook.data.local.entity.SyncQueueEntity
import com.elegen.elegencashbook.data.local.entity.TransactionEntity
import com.elegen.elegencashbook.data.local.prefs.AppPreferences
import com.elegen.elegencashbook.data.mapper.toDomain
import com.elegen.elegencashbook.domain.model.Book
import com.elegen.elegencashbook.domain.model.BookWithBalance
import com.elegen.elegencashbook.domain.model.Business
import com.elegen.elegencashbook.domain.model.BusinessOverview
import com.elegen.elegencashbook.domain.model.EntryType
import com.elegen.elegencashbook.domain.model.Transaction
import androidx.room.withTransaction
import com.elegen.elegencashbook.data.identity.ActiveIdentity
import com.elegen.elegencashbook.data.local.db.AppDatabase
import com.elegen.elegencashbook.domain.repository.BookRepository
import com.elegen.elegencashbook.domain.repository.BusinessRepository
import com.elegen.elegencashbook.domain.repository.LocalDataMaintenance
import com.elegen.elegencashbook.domain.repository.SettingsRepository
import com.elegen.elegencashbook.domain.repository.TransactionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-only repository implementations (spec §6.1: Room is the single source of truth;
 * repositories never talk to the network). Every write stamps the sync envelope so the
 * P4 outbox can pick changes up without schema changes.
 */

private const val DEFAULT_CURRENCY = "PKR"

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

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class BusinessRepositoryImpl @Inject constructor(
    private val dao: BusinessDao,
    private val prefs: AppPreferences,
    private val identity: ActiveIdentity,
    private val outbox: OutboxWriter,
) : BusinessRepository {

    // Re-subscribes on every identity change (login/logout) so the visible set is always the
    // active identity's — never another user's rows.
    override fun observeBusinesses(): Flow<List<BusinessOverview>> =
        identity.activeUid.flatMapLatest { uid ->
            dao.observeWithBookCount(uid).map { rows -> rows.map { it.toDomain() } }
        }

    override suspend fun create(name: String): Business {
        val now = System.currentTimeMillis()
        val entity = BusinessEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            ownerUid = identity.current(),
            currency = DEFAULT_CURRENCY,
            createdAt = now,
            sync = newEnvelope(prefs.deviceId(), now),
        )
        return outbox.write(SyncQueueEntity.TYPE_BUSINESS, entity.id, entity.sync.version, SyncQueueEntity.OP_CREATE) {
            dao.upsert(entity)
            entity.toDomain()
        }
    }
}

@Singleton
class BookRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val dao: BookDao,
    private val transactionDao: TransactionDao,
    private val prefs: AppPreferences,
    private val identity: ActiveIdentity,
    private val outbox: OutboxWriter,
) : BookRepository {

    // Scoped by businessId; access to the business is already the identity gate (BusinessRepository).
    override fun observeBooksWithBalance(businessId: String): Flow<List<BookWithBalance>> =
        dao.observeWithBalance(businessId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun create(businessId: String, name: String): Book {
        val now = System.currentTimeMillis()
        val entity = BookEntity(
            id = UUID.randomUUID().toString(),
            businessId = businessId,
            ownerUid = identity.current(),
            name = name,
            currency = DEFAULT_CURRENCY,
            createdAt = now,
            sync = newEnvelope(prefs.deviceId(), now),
        )
        return outbox.write(SyncQueueEntity.TYPE_BOOK, entity.id, entity.sync.version, SyncQueueEntity.OP_CREATE) {
            dao.upsert(entity)
            entity.toDomain()
        }
    }

    /** Only the owning identity may mutate a book — belt-and-suspenders alongside the UI's own scoping. */
    private suspend fun ownedBook(bookId: String): BookEntity? =
        dao.getById(bookId)?.takeIf { it.ownerUid == identity.current() }

    private suspend fun bump(existing: BookEntity, operation: String, deletedAt: Long? = existing.sync.deletedAt, transform: BookEntity.() -> BookEntity = { this }) {
        val now = System.currentTimeMillis()
        val updated = existing.transform().copy(sync = existing.sync.bumped(prefs.deviceId(), now, deletedAt))
        outbox.write(SyncQueueEntity.TYPE_BOOK, updated.id, updated.sync.version, operation) {
            dao.upsert(updated)
        }
    }

    override suspend fun rename(bookId: String, name: String) {
        val existing = ownedBook(bookId) ?: return
        bump(existing, SyncQueueEntity.OP_UPDATE) { copy(name = name) }
    }

    override suspend fun softDelete(bookId: String) {
        val existing = ownedBook(bookId) ?: return
        bump(existing, SyncQueueEntity.OP_DELETE, deletedAt = System.currentTimeMillis())
    }

    override suspend fun restore(bookId: String) {
        val existing = ownedBook(bookId) ?: return
        bump(existing, SyncQueueEntity.OP_UPDATE, deletedAt = null)
    }

    override suspend fun move(bookId: String, targetBusinessId: String) {
        val existing = ownedBook(bookId) ?: return
        bump(existing, SyncQueueEntity.OP_UPDATE) { copy(businessId = targetBusinessId) }
    }

    override suspend fun duplicate(bookId: String): Book {
        val existing = ownedBook(bookId) ?: error("Book not found")
        val now = System.currentTimeMillis()
        val copy = existing.copy(
            id = UUID.randomUUID().toString(),
            name = "${existing.name} (Copy)",
            ownerUid = identity.current(),
            createdAt = now,
            sync = newEnvelope(prefs.deviceId(), now),
        )
        // Atomic: a crash/kill mid-copy must not leave a half-duplicated book — and every copied
        // row is queued for sync in the same transaction (book before its entries, spec §6.3).
        db.withTransaction {
            dao.upsert(copy)
            outbox.enqueue(SyncQueueEntity.TYPE_BOOK, copy.id, copy.sync.version, SyncQueueEntity.OP_CREATE)
            transactionDao.getAllActiveByBook(bookId).forEach { entry ->
                val newEntry = entry.copy(
                    id = UUID.randomUUID().toString(),
                    bookId = copy.id,
                    createdByUid = identity.current(),
                    sync = newEnvelope(prefs.deviceId(), now),
                )
                transactionDao.upsert(newEntry)
                outbox.enqueue(SyncQueueEntity.TYPE_TRANSACTION, newEntry.id, newEntry.sync.version, SyncQueueEntity.OP_CREATE)
            }
        }
        outbox.requestPush()
        return copy.toDomain()
    }
}

@Singleton
class TransactionRepositoryImpl @Inject constructor(
    private val dao: TransactionDao,
    private val prefs: AppPreferences,
    private val identity: ActiveIdentity,
    private val outbox: OutboxWriter,
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
            createdByUid = identity.current(),
            sync = newEnvelope(prefs.deviceId(), now),
        )
        return outbox.write(SyncQueueEntity.TYPE_TRANSACTION, entity.id, entity.sync.version, SyncQueueEntity.OP_CREATE) {
            dao.upsert(entity)
            entity.toDomain()
        }
    }

    private suspend fun bump(existing: TransactionEntity, operation: String, deletedAt: Long? = existing.sync.deletedAt, transform: TransactionEntity.() -> TransactionEntity = { this }) {
        val now = System.currentTimeMillis()
        val updated = existing.transform().copy(sync = existing.sync.bumped(prefs.deviceId(), now, deletedAt))
        outbox.write(SyncQueueEntity.TYPE_TRANSACTION, updated.id, updated.sync.version, operation) {
            dao.upsert(updated)
        }
    }

    override suspend fun update(transaction: Transaction) {
        val existing = dao.getById(transaction.id) ?: return
        bump(existing, SyncQueueEntity.OP_UPDATE) {
            copy(
                type = transaction.type.name,
                amountPaisa = transaction.amount.paisa,
                description = transaction.description,
                createdAt = transaction.createdAt,
            )
        }
    }

    override suspend fun softDelete(id: String) {
        val existing = dao.getById(id) ?: return
        bump(existing, SyncQueueEntity.OP_DELETE, deletedAt = System.currentTimeMillis())
    }

    override suspend fun restore(id: String) {
        val existing = dao.getById(id) ?: return
        bump(existing, SyncQueueEntity.OP_UPDATE, deletedAt = null)
    }
}

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val prefs: AppPreferences,
) : SettingsRepository {
    override val activeBusinessId: Flow<String?> = prefs.activeBusinessId
    override suspend fun setActiveBusinessId(id: String) = prefs.setActiveBusinessId(id)
    override suspend fun clearActiveBusinessId() = prefs.clearActiveBusinessId()
    override val guestModeChosen: Flow<Boolean> = prefs.guestModeChosen
    override suspend fun setGuestModeChosen(chosen: Boolean) = prefs.setGuestModeChosen(chosen)
}

@Singleton
class LocalDataMaintenanceImpl @Inject constructor(
    private val db: AppDatabase,
    private val prefs: AppPreferences,
) : LocalDataMaintenance {
    override suspend fun wipeAll() = withContext(Dispatchers.IO) {
        db.clearAllTables()
        prefs.clearAll()
    }
}
