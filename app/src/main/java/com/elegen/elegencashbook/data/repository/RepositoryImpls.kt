package com.elegen.elegencashbook.data.repository

import com.elegen.elegencashbook.core.money.Money
import com.elegen.elegencashbook.core.permission.BusinessRole
import com.elegen.elegencashbook.core.permission.Permission
import com.elegen.elegencashbook.core.permission.PermissionBook
import com.elegen.elegencashbook.core.permission.PermissionResolver
import com.elegen.elegencashbook.data.local.dao.BookDao
import com.elegen.elegencashbook.data.local.dao.BookGrantDao
import com.elegen.elegencashbook.data.local.dao.BusinessDao
import com.elegen.elegencashbook.data.local.dao.BusinessMemberDao
import com.elegen.elegencashbook.data.local.dao.HistoryDao
import com.elegen.elegencashbook.data.local.dao.TransactionDao
import com.elegen.elegencashbook.data.local.entity.BookEntity
import com.elegen.elegencashbook.data.local.entity.BusinessEntity
import com.elegen.elegencashbook.data.local.entity.HistoryEntity
import com.elegen.elegencashbook.data.local.entity.SyncEnvelope
import com.elegen.elegencashbook.data.local.entity.SyncQueueEntity
import com.elegen.elegencashbook.data.local.entity.TransactionEntity
import com.elegen.elegencashbook.data.local.prefs.AppPreferences
import com.elegen.elegencashbook.data.mapper.toDomain
import com.elegen.elegencashbook.data.mapper.toPermissionGrant
import com.elegen.elegencashbook.data.mapper.toPermissionMembership
import com.elegen.elegencashbook.domain.model.Book
import com.elegen.elegencashbook.domain.model.BookWithBalance
import com.elegen.elegencashbook.domain.model.Business
import com.elegen.elegencashbook.domain.model.BusinessOverview
import com.elegen.elegencashbook.domain.model.EntryType
import com.elegen.elegencashbook.domain.model.HistoryEntityType
import com.elegen.elegencashbook.domain.model.HistoryEntry
import com.elegen.elegencashbook.domain.model.PermissionDeniedException
import com.elegen.elegencashbook.domain.model.Transaction
import androidx.room.withTransaction
import com.elegen.elegencashbook.data.identity.ActiveIdentity
import com.elegen.elegencashbook.data.local.db.AppDatabase
import com.elegen.elegencashbook.domain.repository.BookRepository
import com.elegen.elegencashbook.domain.repository.BusinessRepository
import com.elegen.elegencashbook.domain.repository.HistoryRepository
import com.elegen.elegencashbook.domain.repository.LocalDataMaintenance
import com.elegen.elegencashbook.domain.repository.PermissionRepository
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

/** "field=old→new;..." for changed fields only; null (no history row) if nothing actually changed. */
internal fun buildChanges(vararg fields: Triple<String, Any?, Any?>): String? {
    val diffs = fields.filter { it.second != it.third }
    if (diffs.isEmpty()) return null
    return diffs.joinToString(";") { "${it.first}=${it.second}→${it.third}" }
}

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class BusinessRepositoryImpl @Inject constructor(
    private val dao: BusinessDao,
    private val prefs: AppPreferences,
    private val identity: ActiveIdentity,
    private val outbox: OutboxWriter,
    private val permissionRepository: PermissionRepository,
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

    /** Same reasoning as BookRepositoryImpl.requireBookCapability — real capability, not raw ownership. */
    private suspend fun requireBusinessCapability(businessId: String, capability: Permission): BusinessEntity {
        val business = dao.getById(businessId) ?: throw PermissionDeniedException("Business not found")
        if (capability !in permissionRepository.effectiveBusinessCapabilities(businessId)) throw PermissionDeniedException()
        return business
    }

    override suspend fun rename(businessId: String, name: String) {
        val existing = requireBusinessCapability(businessId, Permission.BUSINESS_EDIT)
        val now = System.currentTimeMillis()
        val updated = existing.copy(name = name, sync = existing.sync.bumped(prefs.deviceId(), now))
        outbox.write(SyncQueueEntity.TYPE_BUSINESS, updated.id, updated.sync.version, SyncQueueEntity.OP_UPDATE) {
            dao.upsert(updated)
        }
    }

    override suspend fun softDelete(businessId: String) {
        val existing = requireBusinessCapability(businessId, Permission.BUSINESS_DELETE)
        val now = System.currentTimeMillis()
        val updated = existing.copy(sync = existing.sync.bumped(prefs.deviceId(), now, deletedAt = now))
        outbox.write(SyncQueueEntity.TYPE_BUSINESS, updated.id, updated.sync.version, SyncQueueEntity.OP_DELETE) {
            dao.upsert(updated)
        }
    }
}

@Singleton
class BookRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val dao: BookDao,
    private val transactionDao: TransactionDao,
    private val historyDao: HistoryDao,
    private val prefs: AppPreferences,
    private val identity: ActiveIdentity,
    private val outbox: OutboxWriter,
    private val permissionRepository: PermissionRepository,
) : BookRepository {

    // Business-level visibility (BusinessRepository) is NOT enough on its own: a book-scoped
    // member (share_book / invite with a book scope) can see the business but only specific
    // books within it. Real bug found via on-device testing: sharing one book out of four still
    // showed all four, because this only ever filtered by businessId with no per-book check.
    override fun observeBooksWithBalance(businessId: String): Flow<List<BookWithBalance>> =
        dao.observeWithBalance(businessId).map { rows ->
            rows.map { it.toDomain() }
                .filter { Permission.BOOK_VIEW in permissionRepository.effectivePermissions(it.book.id) }
        }

    /** Must be called from inside an active [outbox] transaction — enqueues its own push row too (spec P8: history syncs cross-device via audit_log). */
    private suspend fun logHistory(bookId: String, action: String, changes: String?) {
        val entry = HistoryEntity(
            id = UUID.randomUUID().toString(),
            entityType = HistoryEntity.TYPE_BOOK,
            entityId = bookId,
            bookId = bookId,
            action = action,
            changes = changes,
            actorUid = identity.current(),
            deviceId = prefs.deviceId(),
            at = System.currentTimeMillis(),
        )
        historyDao.insert(entry)
        outbox.enqueue(SyncQueueEntity.TYPE_HISTORY, entry.id, 1, SyncQueueEntity.OP_CREATE)
    }

    override suspend fun create(businessId: String, name: String): Book {
        if (Permission.BOOK_ADD !in permissionRepository.effectiveBusinessCapabilities(businessId)) {
            throw PermissionDeniedException()
        }
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
            logHistory(entity.id, HistoryEntity.ACTION_CREATED, null)
            entity.toDomain()
        }
    }

    /**
     * Real capability check (spec §8.3), not just raw ownership — a shared ADMIN/OWNER
     * collaborator legitimately needs to mutate books they didn't personally create. Without this,
     * Room would happily accept the write locally (offline-first never refuses a write) and the
     * mutation would then either silently vanish on the next pull or sit stuck un-pushed in the
     * outbox forever, with the user never told why (real bug found on-device, P7).
     */
    private suspend fun requireBookCapability(bookId: String, capability: Permission): BookEntity {
        val book = dao.getById(bookId) ?: throw PermissionDeniedException("Book not found")
        if (capability !in permissionRepository.effectivePermissions(bookId)) throw PermissionDeniedException()
        return book
    }

    /** [logIfNoChange] = false skips the history row when [changes] ends up null (e.g. rename to the same name). */
    private suspend fun bump(
        existing: BookEntity,
        operation: String,
        historyAction: String,
        changes: String? = null,
        logIfNoChange: Boolean = true,
        deletedAt: Long? = existing.sync.deletedAt,
        transform: BookEntity.() -> BookEntity = { this },
    ) {
        val now = System.currentTimeMillis()
        val updated = existing.transform().copy(sync = existing.sync.bumped(prefs.deviceId(), now, deletedAt))
        outbox.write(SyncQueueEntity.TYPE_BOOK, updated.id, updated.sync.version, operation) {
            dao.upsert(updated)
            if (changes != null || logIfNoChange) logHistory(updated.id, historyAction, changes)
        }
    }

    override suspend fun rename(bookId: String, name: String) {
        val existing = requireBookCapability(bookId, Permission.BOOK_EDIT)
        val changes = buildChanges(Triple("name", existing.name, name))
        bump(existing, SyncQueueEntity.OP_UPDATE, HistoryEntity.ACTION_RENAMED, changes, logIfNoChange = false) { copy(name = name) }
    }

    override suspend fun softDelete(bookId: String) {
        val existing = requireBookCapability(bookId, Permission.BOOK_DELETE)
        bump(existing, SyncQueueEntity.OP_DELETE, HistoryEntity.ACTION_DELETED, deletedAt = System.currentTimeMillis())
    }

    override suspend fun restore(bookId: String) {
        val existing = requireBookCapability(bookId, Permission.BOOK_DELETE)
        bump(existing, SyncQueueEntity.OP_UPDATE, HistoryEntity.ACTION_RESTORED, deletedAt = null)
    }

    override suspend fun move(bookId: String, targetBusinessId: String) {
        val existing = requireBookCapability(bookId, Permission.BOOK_EDIT)
        val changes = buildChanges(Triple("businessId", existing.businessId, targetBusinessId))
        bump(existing, SyncQueueEntity.OP_UPDATE, HistoryEntity.ACTION_MOVED, changes, logIfNoChange = false) { copy(businessId = targetBusinessId) }
    }

    override suspend fun duplicate(bookId: String): Book {
        val existing = requireBookCapability(bookId, Permission.BOOK_ADD)
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
            logHistory(copy.id, HistoryEntity.ACTION_COPIED, "copiedFrom=$bookId")
            transactionDao.getAllActiveByBook(bookId).forEach { entry ->
                val newEntry = entry.copy(
                    id = UUID.randomUUID().toString(),
                    bookId = copy.id,
                    createdByUid = identity.current(),
                    sync = newEnvelope(prefs.deviceId(), now),
                )
                transactionDao.upsert(newEntry)
                outbox.enqueue(SyncQueueEntity.TYPE_TRANSACTION, newEntry.id, newEntry.sync.version, SyncQueueEntity.OP_CREATE)
                val historyEntry = HistoryEntity(
                    id = UUID.randomUUID().toString(),
                    entityType = HistoryEntity.TYPE_TRANSACTION,
                    entityId = newEntry.id,
                    bookId = copy.id,
                    action = HistoryEntity.ACTION_COPIED,
                    changes = "copiedFrom=${entry.id}",
                    actorUid = identity.current(),
                    deviceId = prefs.deviceId(),
                    at = now,
                )
                historyDao.insert(historyEntry)
                outbox.enqueue(SyncQueueEntity.TYPE_HISTORY, historyEntry.id, 1, SyncQueueEntity.OP_CREATE)
            }
        }
        outbox.requestPush()
        return copy.toDomain()
    }
}

@Singleton
class TransactionRepositoryImpl @Inject constructor(
    private val dao: TransactionDao,
    private val historyDao: HistoryDao,
    private val prefs: AppPreferences,
    private val identity: ActiveIdentity,
    private val outbox: OutboxWriter,
    private val permissionRepository: PermissionRepository,
) : TransactionRepository {

    override fun observeEntries(bookId: String): Flow<List<Transaction>> =
        dao.observeActiveByBook(bookId).map { rows -> rows.map { it.toDomain() } }

    override fun observeById(id: String): Flow<Transaction?> =
        dao.observeById(id).map { it?.toDomain() }

    /** Must be called from inside an active [outbox] transaction — enqueues its own push row too (spec P8: history syncs cross-device via audit_log). */
    private suspend fun logHistory(entryId: String, bookId: String, action: String, changes: String?) {
        val entry = HistoryEntity(
            id = UUID.randomUUID().toString(),
            entityType = HistoryEntity.TYPE_TRANSACTION,
            entityId = entryId,
            bookId = bookId,
            action = action,
            changes = changes,
            actorUid = identity.current(),
            deviceId = prefs.deviceId(),
            at = System.currentTimeMillis(),
        )
        historyDao.insert(entry)
        outbox.enqueue(SyncQueueEntity.TYPE_HISTORY, entry.id, 1, SyncQueueEntity.OP_CREATE)
    }

    /** Same reasoning as BookRepositoryImpl.requireBookCapability — real capability, not raw ownership. */
    private suspend fun requireCapability(bookId: String, capability: Permission) {
        if (capability !in permissionRepository.effectivePermissions(bookId)) throw PermissionDeniedException()
    }

    override suspend fun add(
        bookId: String,
        type: EntryType,
        amount: Money,
        description: String,
        createdAt: Long,
    ): Transaction {
        requireCapability(bookId, Permission.TX_ADD)
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
            logHistory(entity.id, entity.bookId, HistoryEntity.ACTION_CREATED, null)
            entity.toDomain()
        }
    }

    /** [logIfNoChange] = false skips the history row when [changes] ends up null (e.g. an edit that saved with no actual field change). */
    private suspend fun bump(
        existing: TransactionEntity,
        operation: String,
        historyAction: String,
        changes: String? = null,
        logIfNoChange: Boolean = true,
        deletedAt: Long? = existing.sync.deletedAt,
        transform: TransactionEntity.() -> TransactionEntity = { this },
    ) {
        val now = System.currentTimeMillis()
        val updated = existing.transform().copy(sync = existing.sync.bumped(prefs.deviceId(), now, deletedAt))
        outbox.write(SyncQueueEntity.TYPE_TRANSACTION, updated.id, updated.sync.version, operation) {
            dao.upsert(updated)
            if (changes != null || logIfNoChange) logHistory(updated.id, updated.bookId, historyAction, changes)
        }
    }

    override suspend fun update(transaction: Transaction) {
        val existing = dao.getById(transaction.id) ?: throw PermissionDeniedException("Entry not found")
        requireCapability(existing.bookId, Permission.TX_EDIT)
        val changes = buildChanges(
            Triple("type", existing.type, transaction.type.name),
            Triple("amountPaisa", existing.amountPaisa, transaction.amount.paisa),
            Triple("description", existing.description, transaction.description),
            Triple("entryDate", existing.createdAt, transaction.createdAt),
        )
        bump(existing, SyncQueueEntity.OP_UPDATE, HistoryEntity.ACTION_UPDATED, changes, logIfNoChange = false) {
            copy(
                type = transaction.type.name,
                amountPaisa = transaction.amount.paisa,
                description = transaction.description,
                createdAt = transaction.createdAt,
            )
        }
    }

    override suspend fun softDelete(id: String) {
        val existing = dao.getById(id) ?: throw PermissionDeniedException("Entry not found")
        requireCapability(existing.bookId, Permission.TX_DELETE)
        bump(existing, SyncQueueEntity.OP_DELETE, HistoryEntity.ACTION_DELETED, deletedAt = System.currentTimeMillis())
    }

    override suspend fun restore(id: String) {
        val existing = dao.getById(id) ?: throw PermissionDeniedException("Entry not found")
        requireCapability(existing.bookId, Permission.TX_DELETE)
        bump(existing, SyncQueueEntity.OP_UPDATE, HistoryEntity.ACTION_RESTORED, deletedAt = null)
    }

    override suspend fun move(id: String, targetBookId: String) {
        val existing = dao.getById(id) ?: throw PermissionDeniedException("Entry not found")
        requireCapability(existing.bookId, Permission.TX_EDIT)
        requireCapability(targetBookId, Permission.TX_ADD)
        val changes = buildChanges(Triple("bookId", existing.bookId, targetBookId))
        bump(existing, SyncQueueEntity.OP_UPDATE, HistoryEntity.ACTION_MOVED, changes, logIfNoChange = false) { copy(bookId = targetBookId) }
    }

    override suspend fun copyTo(id: String, targetBookId: String): Transaction {
        val existing = dao.getById(id) ?: throw PermissionDeniedException("Entry not found")
        requireCapability(targetBookId, Permission.TX_ADD)
        val now = System.currentTimeMillis()
        val copy = existing.copy(
            id = UUID.randomUUID().toString(),
            bookId = targetBookId,
            createdByUid = identity.current(),
            sync = newEnvelope(prefs.deviceId(), now),
        )
        return outbox.write(SyncQueueEntity.TYPE_TRANSACTION, copy.id, copy.sync.version, SyncQueueEntity.OP_CREATE) {
            dao.upsert(copy)
            logHistory(copy.id, copy.bookId, HistoryEntity.ACTION_COPIED, "copiedFrom=$id")
            copy.toDomain()
        }
    }
}

@Singleton
class HistoryRepositoryImpl @Inject constructor(
    private val dao: HistoryDao,
) : HistoryRepository {
    override fun observeForEntity(entityType: HistoryEntityType, entityId: String): Flow<List<HistoryEntry>> =
        dao.observeForEntity(entityType.name, entityId).map { rows -> rows.map { it.toDomain() } }
}

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PermissionRepositoryImpl @Inject constructor(
    private val businessDao: BusinessDao,
    private val bookDao: BookDao,
    private val businessMemberDao: BusinessMemberDao,
    private val bookGrantDao: BookGrantDao,
    private val identity: ActiveIdentity,
) : PermissionRepository {
    /**
     * The local business_members mirror only populates after a pull cycle — it can legitimately
     * lag well behind "I just created this business/book" (the real root cause behind three
     * separate bugs found on-device this session: Members' invite button, BookAccess's share
     * button, and this one — an owner locked out of renaming/duplicating/deleting their OWN
     * just-created book because their own OWNER membership row hadn't synced down yet). If the
     * resolver comes back empty for a business book purely because the membership lookup missed,
     * but the book's own cached owner_uid is me, treat it as mine — same escape hatch as
     * [effectiveBusinessCapabilities]. Personal books don't need this: [PermissionResolver.effective]
     * already checks owner_uid directly with no membership lookup at all.
     */
    private suspend fun computeEffective(book: BookEntity): Set<Permission> {
        val uid = identity.current()
        val permBook = PermissionBook(book.businessId, book.ownerUid)
        val membership = book.businessId
            ?.let { businessMemberDao.getActiveMembership(it, uid) }
            ?.toPermissionMembership()
        val grant = bookGrantDao.getActiveGrant(book.id, uid)?.toPermissionGrant()
        val resolved = PermissionResolver.effective(uid, permBook, membership, grant)
        if (resolved.isEmpty() && book.businessId != null && book.ownerUid == uid) return Permission.entries.toSet()
        return resolved
    }

    override fun observeEffectivePermissions(bookId: String): Flow<Set<Permission>> =
        bookDao.observeById(bookId).map { book -> book?.let { computeEffective(it) } ?: emptySet() }

    override suspend fun effectivePermissions(bookId: String): Set<Permission> {
        val book = bookDao.getById(bookId) ?: return emptySet()
        return computeEffective(book)
    }

    override suspend fun effectiveBusinessCapabilities(businessId: String): Set<Permission> {
        val uid = identity.current()
        val membership = businessMemberDao.getActiveMembership(businessId, uid)
        if (membership != null) return BusinessRole.valueOf(membership.role).defaults()
        val business = businessDao.getById(businessId)
        return if (business?.ownerUid == uid) Permission.entries.toSet() else emptySet()
    }

    override suspend fun myBusinessRole(businessId: String): BusinessRole? {
        val uid = identity.current()
        businessMemberDao.getActiveMembership(businessId, uid)?.let { return BusinessRole.valueOf(it.role) }
        // Same mirror-lag gap as computeEffective() above: a just-created business has no local
        // business_members row yet (pull cycle hasn't run), but its creator is still the OWNER.
        return if (businessDao.getById(businessId)?.ownerUid == uid) BusinessRole.OWNER else null
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
