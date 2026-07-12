package com.elegen.elegencashbook.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.elegen.elegencashbook.data.identity.ActiveIdentity
import com.elegen.elegencashbook.data.local.dao.TransactionDao
import com.elegen.elegencashbook.data.local.db.AppDatabase
import com.elegen.elegencashbook.domain.model.PermissionDeniedException
import com.elegen.elegencashbook.domain.repository.SyncScheduler
import com.elegen.elegencashbook.data.local.entity.BookGrantEntity
import com.elegen.elegencashbook.data.local.entity.BusinessEntity
import com.elegen.elegencashbook.data.local.entity.BusinessMemberEntity
import com.elegen.elegencashbook.data.local.entity.SyncEnvelope
import com.elegen.elegencashbook.data.local.entity.TransactionEntity
import com.elegen.elegencashbook.data.local.prefs.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Fake — real IdentityManager needs an auth graph this repo-level test doesn't care about. */
private class FakeIdentity(uid: String) : ActiveIdentity {
    override val activeUid = MutableStateFlow(uid)
    override fun current(): String = activeUid.value
}

/** No WorkManager in unit tests; the push trigger is verified separately at the worker level. */
private object NoopScheduler : SyncScheduler {
    override fun requestPush() = Unit
    override fun requestPull() = Unit
    override fun schedulePeriodicPull() = Unit
    override fun scheduleCleanup() = Unit
    override fun observePullActive() = kotlinx.coroutines.flow.flowOf(false)
}

/** Delegates everything to [real] except upsert, which fails on the Nth+1 call — simulates a crash mid-copy. */
private class ThrowingAfterNTransactionDao(
    private val real: TransactionDao,
    private val throwAfter: Int,
) : TransactionDao by real {
    private var calls = 0
    override suspend fun upsert(entity: TransactionEntity) {
        calls++
        if (calls > throwAfter) error("boom")
        real.upsert(entity)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BookRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var prefs: AppPreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        prefs = AppPreferences(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun envelope() = SyncEnvelope(1, 1L, "dev", null, SyncEnvelope.STATE_PENDING)

    private fun outbox() = OutboxWriter(db, db.syncQueueDao(), NoopScheduler)

    private fun repo(identityUid: String = "u1", transactionDao: TransactionDao = db.transactionDao()) =
        BookRepositoryImpl(
            db, db.bookDao(), transactionDao, db.historyDao(), prefs, FakeIdentity(identityUid), outbox(),
            PermissionRepositoryImpl(db.businessDao(), db.bookDao(), db.businessMemberDao(), db.bookGrantDao(), FakeIdentity(identityUid)),
        )

    private suspend fun seedBusinessAndBook(owner: String = "u1") {
        db.businessDao().upsert(BusinessEntity("biz", "Biz", owner, "PKR", 1L, envelope()))
        db.bookDao().upsert(
            com.elegen.elegencashbook.data.local.entity.BookEntity("bk", "biz", owner, "Book", "PKR", 1L, envelope())
        )
    }

    @Test
    fun `rename updates the name and bumps version`() = runBlocking {
        seedBusinessAndBook()
        repo().rename("bk", "Renamed")
        val book = db.bookDao().getById("bk")!!
        assertEquals("Renamed", book.name)
        assertEquals(2, book.sync.version)
    }

    @Test
    fun `softDelete then restore round trips`() = runBlocking {
        seedBusinessAndBook()
        val r = repo()
        r.softDelete("bk")
        assertTrue(db.bookDao().observeWithBalance("biz").first().isEmpty())
        r.restore("bk")
        assertEquals(listOf("bk"), db.bookDao().observeWithBalance("biz").first().map { it.book.id })
    }

    @Test
    fun `move changes businessId so the book leaves the old business list`() = runBlocking {
        seedBusinessAndBook()
        db.businessDao().upsert(BusinessEntity("biz2", "Biz2", "u1", "PKR", 1L, envelope()))
        repo().move("bk", "biz2")
        assertTrue(db.bookDao().observeWithBalance("biz").first().isEmpty())
        assertEquals(listOf("bk"), db.bookDao().observeWithBalance("biz2").first().map { it.book.id })
    }

    @Test
    fun `mutations are rejected for a book with no local membership or ownership`() = runBlocking {
        seedBusinessAndBook(owner = "someone-else")
        val r = repo(identityUid = "u1")
        assertThrows(PermissionDeniedException::class.java) { runBlocking { r.rename("bk", "Hijacked") } }
        assertThrows(PermissionDeniedException::class.java) { runBlocking { r.softDelete("bk") } }
        val book = db.bookDao().getById("bk")!!
        assertEquals("Book", book.name) // unchanged
        assertNull(book.sync.deletedAt) // not deleted
    }

    @Test
    fun `book-scoped VIEWER only sees the allow-listed book, not every book in the business`() = runBlocking {
        // Real bug found via on-device testing: share_book (or invite with a book scope) makes
        // the business itself visible via a book_scoped membership row, but observeBooksWithBalance
        // only ever filtered by businessId -- with zero per-book check -- so a member scoped to
        // ONE book still saw every book the business had.
        db.businessDao().upsert(BusinessEntity("biz", "Biz", "owner", "PKR", 1L, envelope()))
        db.bookDao().upsert(com.elegen.elegencashbook.data.local.entity.BookEntity("bk1", "biz", "owner", "Shared Book", "PKR", 1L, envelope()))
        db.bookDao().upsert(com.elegen.elegencashbook.data.local.entity.BookEntity("bk2", "biz", "owner", "Other Book", "PKR", 1L, envelope()))
        db.businessMemberDao().upsert(BusinessMemberEntity("m1", "biz", "viewer", "VIEWER", "ACTIVE", bookScoped = true, null, 1L, 1L))
        db.bookGrantDao().upsert(BookGrantEntity("g1", "bk1", "viewer", "ALLOW", null, "owner", 1L, 1L, null))

        val books = repo(identityUid = "viewer").observeBooksWithBalance("biz").first()

        assertEquals(listOf("bk1"), books.map { it.book.id })
    }

    @Test
    fun `duplicate copies the book and its live entries, skipping tombstones`() = runBlocking {
        seedBusinessAndBook()
        db.transactionDao().upsert(TransactionEntity("t1", "bk", "CASH_IN", 100, null, "", 1L, "u1", envelope()))
        db.transactionDao().upsert(TransactionEntity("t2", "bk", "CASH_OUT", 50, null, "", 2L, "u1", envelope()))
        db.transactionDao().upsert(
            TransactionEntity("t3", "bk", "CASH_IN", 999, null, "", 3L, "u1", envelope().copy(deletedAt = 9L))
        )

        val copy = repo().duplicate("bk")

        assertEquals("Book (Copy)", copy.name)
        val copiedEntries = db.transactionDao().getAllActiveByBook(copy.id)
        assertEquals(2, copiedEntries.size) // tombstoned t3 excluded
        assertEquals(setOf(100L, 50L), copiedEntries.map { it.amountPaisa }.toSet())
        // Original untouched.
        assertEquals(2, db.transactionDao().getAllActiveByBook("bk").size)
    }

    @Test
    fun `duplicate is atomic — a failure mid-copy rolls back the whole operation`() = runBlocking {
        seedBusinessAndBook()
        db.transactionDao().upsert(TransactionEntity("t1", "bk", "CASH_IN", 100, null, "", 1L, "u1", envelope()))
        db.transactionDao().upsert(TransactionEntity("t2", "bk", "CASH_IN", 200, null, "", 2L, "u1", envelope()))

        val throwingDao = ThrowingAfterNTransactionDao(db.transactionDao(), throwAfter = 0)
        val r = repo(transactionDao = throwingDao)

        assertThrows(IllegalStateException::class.java) { runBlocking { r.duplicate("bk") } }

        // Nothing committed: no "(Copy)" book, original books/entries unaffected.
        val books = db.bookDao().observeWithBalance("biz").first()
        assertEquals(1, books.size)
        assertEquals(2, db.transactionDao().getAllActiveByBook("bk").size)
    }

    @Test
    fun `every mutation queues an outbox row atomically`() = runBlocking {
        seedBusinessAndBook()
        val r = repo()
        r.create("biz", "New")          // 1 book create
        r.rename("bk", "Renamed")       // 1 book update
        // One outbox row per write; both committed alongside their entity.
        assertEquals(2, db.syncQueueDao().pending().size)
    }

    @Test
    fun `rollback also discards the outbox rows (no orphan queue entry)`() = runBlocking {
        seedBusinessAndBook()
        val throwingDao = ThrowingAfterNTransactionDao(db.transactionDao(), throwAfter = 0)
        db.transactionDao().upsert(TransactionEntity("t1", "bk", "CASH_IN", 100, null, "", 1L, "u1", envelope()))

        assertThrows(IllegalStateException::class.java) {
            runBlocking { repo(transactionDao = throwingDao).duplicate("bk") }
        }
        // The book-copy outbox row enqueued before the failing entry must be rolled back too.
        assertTrue(db.syncQueueDao().pending().isEmpty())
    }
}
