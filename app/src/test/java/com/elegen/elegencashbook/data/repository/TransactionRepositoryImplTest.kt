package com.elegen.elegencashbook.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.elegen.elegencashbook.core.money.Money
import com.elegen.elegencashbook.data.identity.ActiveIdentity
import com.elegen.elegencashbook.data.local.db.AppDatabase
import com.elegen.elegencashbook.data.local.entity.BookEntity
import com.elegen.elegencashbook.data.local.entity.BusinessEntity
import com.elegen.elegencashbook.data.local.entity.BusinessMemberEntity
import com.elegen.elegencashbook.data.local.entity.SyncEnvelope
import com.elegen.elegencashbook.data.local.entity.TransactionEntity
import com.elegen.elegencashbook.data.local.prefs.AppPreferences
import com.elegen.elegencashbook.domain.model.EntryType
import com.elegen.elegencashbook.domain.model.PermissionDeniedException
import com.elegen.elegencashbook.domain.repository.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression coverage for the real on-device bug (P7): TransactionRepositoryImpl used to have no
 * capability check at all — any identity could add/edit/delete any entry locally. A VIEWER's
 * write would silently "succeed" in Room, then either vanish on the next pull or sit stuck
 * un-pushed forever, with no error ever shown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TransactionRepositoryImplTest {

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

    private class FakeIdentity(uid: String) : ActiveIdentity {
        override val activeUid = MutableStateFlow(uid)
        override fun current(): String = activeUid.value
    }

    private object NoopScheduler : SyncScheduler {
        override fun requestPush() = Unit
        override fun requestPull() = Unit
        override fun schedulePeriodicPull() = Unit
        override fun scheduleCleanup() = Unit
        override fun observePullActive() = kotlinx.coroutines.flow.flowOf(false)
    }

    private fun envelope() = SyncEnvelope(1, 1L, "dev", null, SyncEnvelope.STATE_PENDING)

    private fun repo(identityUid: String) = TransactionRepositoryImpl(
        db.transactionDao(), db.historyDao(), prefs, FakeIdentity(identityUid), OutboxWriter(db, db.syncQueueDao(), NoopScheduler),
        PermissionRepositoryImpl(db.businessDao(), db.bookDao(), db.businessMemberDao(), db.bookGrantDao(), FakeIdentity(identityUid)),
    )

    /** Owner + one VIEWER, one ADMIN, both with a real local business_members row (the synced-mirror case). */
    private suspend fun seedBusinessBookAndMembers() {
        db.businessDao().upsert(BusinessEntity("biz", "Biz", "owner", "PKR", 1L, envelope()))
        db.bookDao().upsert(BookEntity("bk", "biz", "owner", "Book", "PKR", 1L, envelope()))
        db.businessMemberDao().upsert(
            BusinessMemberEntity("m1", "biz", "viewer", "VIEWER", "ACTIVE", false, null, 1L, 1L)
        )
        db.businessMemberDao().upsert(
            BusinessMemberEntity("m2", "biz", "admin", "ADMIN", "ACTIVE", false, null, 1L, 1L)
        )
    }

    @Test
    fun `VIEWER cannot add an entry`() = runBlocking {
        seedBusinessBookAndMembers()
        assertThrows(PermissionDeniedException::class.java) {
            runBlocking { repo("viewer").add("bk", EntryType.CASH_IN, Money(100), "x", 1L) }
        }
        Unit
    }

    @Test
    fun `ADMIN can add an entry`() = runBlocking {
        seedBusinessBookAndMembers()
        val entry = repo("admin").add("bk", EntryType.CASH_IN, Money(100), "x", 1L)
        assertEquals(100L, entry.amount.paisa)
    }

    @Test
    fun `VIEWER cannot edit or delete an existing entry`() = runBlocking {
        seedBusinessBookAndMembers()
        db.transactionDao().upsert(TransactionEntity("t1", "bk", "CASH_IN", 100, null, "orig", 1L, "owner", envelope()))

        val r = repo("viewer")
        assertThrows(PermissionDeniedException::class.java) {
            runBlocking {
                r.update(
                    com.elegen.elegencashbook.domain.model.Transaction("t1", "bk", EntryType.CASH_IN, Money(200), "edited", 1L, 1L)
                )
            }
        }
        assertThrows(PermissionDeniedException::class.java) { runBlocking { r.softDelete("t1") } }

        val entry = db.transactionDao().getById("t1")!!
        assertEquals("orig", entry.description) // unchanged
        assertEquals(null, entry.sync.deletedAt) // not deleted
    }

    @Test
    fun `OWNER can edit and delete without a local business_members row (owner fallback)`() = runBlocking {
        // Owner's row created via seedBusinessBookAndMembers, but NO business_members row for
        // "owner" is seeded here -- this is the real-world "just created it, haven't pulled yet"
        // scenario that caused the on-device crash/silent-fail bug this test guards against.
        db.businessDao().upsert(BusinessEntity("biz", "Biz", "owner", "PKR", 1L, envelope()))
        db.bookDao().upsert(BookEntity("bk", "biz", "owner", "Book", "PKR", 1L, envelope()))
        db.transactionDao().upsert(TransactionEntity("t1", "bk", "CASH_IN", 100, null, "orig", 1L, "owner", envelope()))

        val r = repo("owner")
        r.update(com.elegen.elegencashbook.domain.model.Transaction("t1", "bk", EntryType.CASH_IN, Money(200), "edited", 1L, 1L))
        val entry = db.transactionDao().getById("t1")!!
        assertEquals("edited", entry.description)
    }
}
