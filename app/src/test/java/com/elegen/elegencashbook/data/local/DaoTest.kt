package com.elegen.elegencashbook.data.local

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.elegen.elegencashbook.data.local.db.AppDatabase
import com.elegen.elegencashbook.data.local.entity.BookEntity
import com.elegen.elegencashbook.data.local.entity.BusinessEntity
import com.elegen.elegencashbook.data.local.entity.BusinessMemberEntity
import com.elegen.elegencashbook.data.local.entity.SyncEnvelope
import com.elegen.elegencashbook.data.local.entity.SyncQueueEntity
import com.elegen.elegencashbook.data.local.entity.TransactionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Runs on JVM via Robolectric (JUnit4, executed by the vintage engine).
 * Uses a plain Application (not ElegenApp) so the Hilt/Supabase graph isn't booted for DAO tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun envelope(deletedAt: Long? = null) = SyncEnvelope(
        version = 1, updatedAt = 1L, deviceId = "dev", deletedAt = deletedAt,
        syncState = SyncEnvelope.STATE_PENDING,
    )

    private fun business(id: String, owner: String = "u1", name: String = id) =
        BusinessEntity(id, name, owner, "PKR", 1L, envelope())

    private fun book(id: String, businessId: String, owner: String = "u1", deletedAt: Long? = null) =
        BookEntity(id, businessId, owner, id, "PKR", 1L, envelope(deletedAt))

    private fun entry(
        id: String, bookId: String, type: String, paisa: Long,
        createdBy: String = "u1", createdAt: Long = 1L, updatedAt: Long = createdAt, deletedAt: Long? = null,
    ) = TransactionEntity(id, bookId, type, paisa, null, "", createdAt, createdBy, envelope(deletedAt).copy(updatedAt = updatedAt))

    @Test
    fun `business book counts exclude deleted books`() = runBlocking {
        db.businessDao().upsert(business("biz1"))
        db.businessDao().upsert(business("biz2"))
        db.bookDao().upsert(book("bk1", "biz1"))
        db.bookDao().upsert(book("bk2", "biz1"))
        db.bookDao().upsert(book("bk3", "biz1", deletedAt = 9L))
        db.bookDao().upsert(book("bk4", "biz2"))

        val rows = db.businessDao().observeWithBookCount("u1").first()
        assertEquals(listOf("biz1" to 2, "biz2" to 1), rows.map { it.business.id to it.bookCount })
    }

    @Test
    fun `businesses are isolated by owner`() = runBlocking {
        db.businessDao().upsert(business("mine", owner = "u1"))
        db.businessDao().upsert(business("theirs", owner = "u2"))
        db.businessDao().upsert(business("guest_biz", owner = "guest"))

        assertEquals(listOf("mine"), db.businessDao().observeWithBookCount("u1").first().map { it.business.id })
        assertEquals(listOf("theirs"), db.businessDao().observeWithBookCount("u2").first().map { it.business.id })
        assertEquals(listOf("guest_biz"), db.businessDao().observeWithBookCount("guest").first().map { it.business.id })
    }

    @Test
    fun `claim moves guest rows to the uid and leaves others untouched`() = runBlocking {
        db.businessDao().upsert(business("g_biz", owner = "guest"))
        db.businessDao().upsert(business("other", owner = "u2"))
        db.bookDao().upsert(book("g_book", "g_biz", owner = "guest"))
        db.transactionDao().upsert(entry("g_tx", "g_book", "CASH_IN", 500, createdBy = "guest"))

        val now = 42L
        db.businessDao().claimOwner("guest", "u1", now)
        db.bookDao().claimOwner("guest", "u1", now)
        db.transactionDao().claimCreator("guest", "u1", now)

        // Now visible under u1, gone from guest, u2 unaffected.
        assertEquals(listOf("g_biz"), db.businessDao().observeWithBookCount("u1").first().map { it.business.id })
        assertEquals(emptyList<String>(), db.businessDao().observeWithBookCount("guest").first().map { it.business.id })
        assertEquals(listOf("other"), db.businessDao().observeWithBookCount("u2").first().map { it.business.id })

        val claimedBook = db.bookDao().getById("g_book")!!
        assertEquals("u1", claimedBook.ownerUid)
        assertEquals(SyncEnvelope.STATE_PENDING, claimedBook.sync.syncState)
        assertEquals(now, claimedBook.sync.updatedAt)
        assertEquals("u1", db.transactionDao().getById("g_tx")!!.createdByUid)
    }

    @Test
    fun `getAllVisible mirrors observeWithBookCount's visibility predicate as a one-shot list`() = runBlocking {
        db.businessDao().upsert(business("mine", owner = "u1"))
        db.businessDao().upsert(business("shared", owner = "owner2"))
        db.businessDao().upsert(business("stranger", owner = "u2"))
        db.businessMemberDao().upsert(BusinessMemberEntity("m1", "shared", "u1", "VIEWER", "ACTIVE", false, null, 1L, 1L))

        assertEquals(setOf("mine", "shared"), db.businessDao().getAllVisible("u1").map { it.id }.toSet())
    }

    @Test
    fun `markAccessLost hides a business locally without touching syncState or version (revocation-sync)`() = runBlocking {
        db.businessDao().upsert(business("biz"))
        db.bookDao().upsert(book("bk", "biz"))

        db.businessDao().markAccessLost("biz", now = 99L)
        db.bookDao().markAccessLost("bk", now = 99L)

        assertEquals(emptyList<String>(), db.businessDao().getAllVisible("u1").map { it.id })
        assertEquals(emptyList<String>(), db.bookDao().getAllForBusinesses(listOf("biz")).map { it.id })
        // Local-only tombstone: never mark PENDING / bump version, or the outbox would try to push
        // this "delete" to a server that never asked for one.
        val biz = db.businessDao().getById("biz")!!
        assertEquals(99L, biz.sync.deletedAt)
        assertEquals(SyncEnvelope.STATE_PENDING, biz.sync.syncState) // unchanged from the fixture's own envelope() default
        assertEquals(1L, biz.sync.version) // unchanged
    }

    @Test
    fun `getAllTombstonedNotOwned and clearAccessLost repair a regained-access tombstone (revocation-sync reverse)`() = runBlocking {
        db.businessDao().upsert(business("biz", owner = "owner2"))
        db.bookDao().upsert(book("bk", "biz", owner = "owner2"))
        db.businessMemberDao().upsert(BusinessMemberEntity("m1", "biz", "u1", "VIEWER", "ACTIVE", false, null, 1L, 1L))
        db.businessDao().markAccessLost("biz", now = 99L)
        db.bookDao().markAccessLost("bk", now = 99L)

        assertEquals(listOf("biz"), db.businessDao().getAllTombstonedNotOwned("u1").map { it.id })
        assertEquals(listOf("bk"), db.bookDao().getAllTombstonedNotOwned("u1").map { it.id })

        db.businessDao().clearAccessLost("biz")
        db.bookDao().clearAccessLost("bk")

        assertEquals(listOf("biz"), db.businessDao().getAllVisible("u1").map { it.id })
        assertEquals(listOf("bk"), db.bookDao().getAllForBusinesses(listOf("biz")).map { it.id })
        assertEquals(emptyList<String>(), db.businessDao().getAllTombstonedNotOwned("u1").map { it.id })
    }

    @Test
    fun `getAllForBusinesses scopes to the given businesses and excludes tombstoned books`() = runBlocking {
        db.businessDao().upsert(business("biz1"))
        db.businessDao().upsert(business("biz2"))
        db.bookDao().upsert(book("bk1", "biz1"))
        db.bookDao().upsert(book("bk2", "biz1", deletedAt = 9L))
        db.bookDao().upsert(book("bk3", "biz2"))

        assertEquals(listOf("bk1"), db.bookDao().getAllForBusinesses(listOf("biz1")).map { it.id })
        assertEquals(setOf("bk1", "bk3"), db.bookDao().getAllForBusinesses(listOf("biz1", "biz2")).map { it.id }.toSet())
    }

    @Test
    fun `book balances sum only live entries of that book`() = runBlocking {
        db.businessDao().upsert(business("biz"))
        db.bookDao().upsert(book("bk", "biz"))
        db.bookDao().upsert(book("other", "biz"))
        db.transactionDao().upsert(entry("t1", "bk", "CASH_IN", 1000, createdAt = 1))
        db.transactionDao().upsert(entry("t2", "bk", "CASH_IN", 20, createdAt = 2))
        // Backdated entry: user picked an old date (createdAt = 3) but it was actually saved just now.
        db.transactionDao().upsert(entry("t3", "bk", "CASH_OUT", 300, createdAt = 3, updatedAt = 500))
        db.transactionDao().upsert(entry("t4", "bk", "CASH_IN", 7777, createdAt = 4, deletedAt = 9L)) // tombstoned
        db.transactionDao().upsert(entry("t5", "other", "CASH_IN", 50000, createdAt = 5))

        val row = db.bookDao().observeWithBalance("biz").first().first { it.book.id == "bk" }
        assertEquals(1020L, row.totalInPaisa)
        assertEquals(300L, row.totalOutPaisa)
        assertEquals(3, row.entryCount)
        // Reflects the real write time (500), not the backdated entry's chosen date (3).
        assertEquals(500L, row.lastEntryAt)
    }

    @Test
    fun `empty book reports zero balance not null`() = runBlocking {
        db.businessDao().upsert(business("biz"))
        db.bookDao().upsert(book("bk", "biz"))

        val row = db.bookDao().observeWithBalance("biz").first().single()
        assertEquals(0L, row.totalInPaisa)
        assertEquals(0L, row.totalOutPaisa)
        assertEquals(0, row.entryCount)
        // No entries yet: falls back to the book's own last-touched time, never null.
        assertEquals(1L, row.lastEntryAt)
    }

    @Test
    fun `entries are chronological and tombstones are filtered`() = runBlocking {
        db.transactionDao().upsert(entry("t2", "bk", "CASH_IN", 2, createdAt = 200))
        db.transactionDao().upsert(entry("t1", "bk", "CASH_IN", 1, createdAt = 100))
        db.transactionDao().upsert(entry("t3", "bk", "CASH_IN", 3, createdAt = 150, deletedAt = 9L))

        val list = db.transactionDao().observeActiveByBook("bk").first()
        assertEquals(listOf("t1", "t2"), list.map { it.id })
    }

    // --- Outbox (SyncQueue) — spec §6.5 ---

    private fun outboxRow(key: String, type: String = "BOOK", id: String = "e1", version: Long = 1) =
        SyncQueueEntity(
            idempotencyKey = key, entityType = type, entityId = id,
            operation = SyncQueueEntity.OP_CREATE, payloadVersion = version,
        )

    @Test
    fun `pending rows drain in insertion order`() = runBlocking {
        val dao = db.syncQueueDao()
        dao.insert(outboxRow("a:1", id = "a"))
        dao.insert(outboxRow("b:1", id = "b"))
        dao.insert(outboxRow("c:1", id = "c"))
        assertEquals(listOf("a", "b", "c"), dao.pending().map { it.entityId })
    }

    @Test
    fun `pending tiers business then book then transaction regardless of insertion order`() = runBlocking {
        val dao = db.syncQueueDao()
        // Simulates a backfill: a transaction row queued first (lower id), its parents backfilled later.
        dao.insert(outboxRow("tx:1", type = "TRANSACTION", id = "tx"))
        dao.insert(outboxRow("book:1", type = "BOOK", id = "book"))
        dao.insert(outboxRow("biz:1", type = "BUSINESS", id = "biz"))

        assertEquals(listOf("biz", "book", "tx"), dao.pending().map { it.entityId })
    }

    @Test
    fun `duplicate idempotency key is ignored (idempotent replay)`() = runBlocking {
        val dao = db.syncQueueDao()
        dao.insert(outboxRow("e1:1"))
        dao.insert(outboxRow("e1:1")) // same write enqueued twice
        assertEquals(1, dao.pending().size)
    }

    @Test
    fun `recordAttempt bumps retry and transitions to dead letter`() = runBlocking {
        val dao = db.syncQueueDao()
        dao.insert(outboxRow("e1:1"))
        val row = dao.pending().single()

        dao.recordAttempt(row.id, retryCount = 1, now = 10L, status = SyncQueueEntity.STATE_PENDING)
        assertEquals(1, dao.pending().single().retryCount) // still drainable

        dao.recordAttempt(row.id, retryCount = 5, now = 20L, status = SyncQueueEntity.STATE_DEAD_LETTER)
        assertEquals(0, dao.pending().size) // no longer PENDING
        assertEquals(1, dao.countByStatus(SyncQueueEntity.STATE_DEAD_LETTER))
    }

    @Test
    fun `markSynced only clears the pushed version, not a newer edit`() = runBlocking {
        db.transactionDao().upsert(entry("t1", "bk", "CASH_IN", 100, updatedAt = 1L).let {
            it.copy(sync = it.sync.copy(version = 2))
        })
        // A concurrent edit advanced the row to version 3 before the push ack lands for version 2.
        val current = db.transactionDao().getById("t1")!!.copy(
            sync = db.transactionDao().getById("t1")!!.sync.copy(version = 3, syncState = SyncEnvelope.STATE_PENDING)
        )
        db.transactionDao().upsert(current)

        db.transactionDao().markSynced("t1", version = 2) // stale ack — must not mark synced
        assertEquals(SyncEnvelope.STATE_PENDING, db.transactionDao().getById("t1")!!.sync.syncState)

        db.transactionDao().markSynced("t1", version = 3) // ack for the live version
        assertEquals(SyncEnvelope.STATE_SYNCED, db.transactionDao().getById("t1")!!.sync.syncState)
    }

    @Test
    fun `soft delete and restore round trip via upsert`() = runBlocking {
        db.transactionDao().upsert(entry("t1", "bk", "CASH_IN", 100))
        val stored = db.transactionDao().getById("t1")!!

        db.transactionDao().upsert(stored.copy(sync = stored.sync.copy(deletedAt = 5L, version = 2)))
        assertEquals(0, db.transactionDao().observeActiveByBook("bk").first().size)

        val deleted = db.transactionDao().getById("t1")!!
        db.transactionDao().upsert(deleted.copy(sync = deleted.sync.copy(deletedAt = null, version = 3)))
        assertEquals(1, db.transactionDao().observeActiveByBook("bk").first().size)
    }
}
