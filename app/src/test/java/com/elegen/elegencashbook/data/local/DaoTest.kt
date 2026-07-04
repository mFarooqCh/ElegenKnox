package com.elegen.elegencashbook.data.local

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.elegen.elegencashbook.data.local.db.AppDatabase
import com.elegen.elegencashbook.data.local.entity.BookEntity
import com.elegen.elegencashbook.data.local.entity.BusinessEntity
import com.elegen.elegencashbook.data.local.entity.SyncEnvelope
import com.elegen.elegencashbook.data.local.entity.TransactionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        createdBy: String = "u1", createdAt: Long = 1L, deletedAt: Long? = null,
    ) = TransactionEntity(id, bookId, type, paisa, null, "", createdAt, createdBy, envelope(deletedAt))

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
    fun `book balances sum only live entries of that book`() = runBlocking {
        db.businessDao().upsert(business("biz"))
        db.bookDao().upsert(book("bk", "biz"))
        db.bookDao().upsert(book("other", "biz"))
        db.transactionDao().upsert(entry("t1", "bk", "CASH_IN", 1000, createdAt = 1))
        db.transactionDao().upsert(entry("t2", "bk", "CASH_IN", 20, createdAt = 2))
        db.transactionDao().upsert(entry("t3", "bk", "CASH_OUT", 300, createdAt = 3))
        db.transactionDao().upsert(entry("t4", "bk", "CASH_IN", 7777, createdAt = 4, deletedAt = 9L)) // tombstoned
        db.transactionDao().upsert(entry("t5", "other", "CASH_IN", 50000, createdAt = 5))

        val row = db.bookDao().observeWithBalance("biz").first().first { it.book.id == "bk" }
        assertEquals(1020L, row.totalInPaisa)
        assertEquals(300L, row.totalOutPaisa)
        assertEquals(3, row.entryCount)
        assertEquals(3L, row.lastEntryAt)
    }

    @Test
    fun `empty book reports zero balance not null`() = runBlocking {
        db.businessDao().upsert(business("biz"))
        db.bookDao().upsert(book("bk", "biz"))

        val row = db.bookDao().observeWithBalance("biz").first().single()
        assertEquals(0L, row.totalInPaisa)
        assertEquals(0L, row.totalOutPaisa)
        assertEquals(0, row.entryCount)
        assertNull(row.lastEntryAt)
    }

    @Test
    fun `entries are chronological and tombstones are filtered`() = runBlocking {
        db.transactionDao().upsert(entry("t2", "bk", "CASH_IN", 2, createdAt = 200))
        db.transactionDao().upsert(entry("t1", "bk", "CASH_IN", 1, createdAt = 100))
        db.transactionDao().upsert(entry("t3", "bk", "CASH_IN", 3, createdAt = 150, deletedAt = 9L))

        val list = db.transactionDao().observeActiveByBook("bk").first()
        assertEquals(listOf("t1", "t2"), list.map { it.id })
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
