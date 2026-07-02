package com.elegen.elegencashbook.data.local

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

/** Runs on JVM via Robolectric (JUnit4, executed by the vintage engine). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
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

    private fun business(id: String, name: String = id) =
        BusinessEntity(id, name, "local", "PKR", 1L, envelope())

    private fun book(id: String, businessId: String, deletedAt: Long? = null) =
        BookEntity(id, businessId, "local", id, "PKR", 1L, envelope(deletedAt))

    private fun entry(
        id: String, bookId: String, type: String, paisa: Long,
        createdAt: Long = 1L, deletedAt: Long? = null,
    ) = TransactionEntity(id, bookId, type, paisa, null, "", createdAt, "local", envelope(deletedAt))

    @Test
    fun `business book counts exclude deleted books`() = runBlocking {
        db.businessDao().upsert(business("biz1"))
        db.businessDao().upsert(business("biz2"))
        db.bookDao().upsert(book("bk1", "biz1"))
        db.bookDao().upsert(book("bk2", "biz1"))
        db.bookDao().upsert(book("bk3", "biz1", deletedAt = 9L))
        db.bookDao().upsert(book("bk4", "biz2"))

        val rows = db.businessDao().observeWithBookCount().first()
        assertEquals(listOf("biz1" to 2, "biz2" to 1), rows.map { it.business.id to it.bookCount })
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
