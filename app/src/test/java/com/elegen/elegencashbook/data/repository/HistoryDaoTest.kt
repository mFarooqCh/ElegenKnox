package com.elegen.elegencashbook.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.elegen.elegencashbook.data.local.entity.HistoryEntity
import com.elegen.elegencashbook.data.local.db.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HistoryDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun row(id: String, entityType: String, entityId: String, bookId: String, action: String, at: Long, changes: String? = null) =
        HistoryEntity(id, entityType, entityId, bookId, action, changes, "u1", "dev1", at)

    @Test
    fun `observeForEntity returns newest first, scoped to that entity only`() = runBlocking {
        db.historyDao().insert(row("h1", HistoryEntity.TYPE_TRANSACTION, "t1", "bk", HistoryEntity.ACTION_CREATED, at = 1L))
        db.historyDao().insert(row("h2", HistoryEntity.TYPE_TRANSACTION, "t1", "bk", HistoryEntity.ACTION_UPDATED, at = 2L, changes = "amountPaisa=100→200"))
        db.historyDao().insert(row("h3", HistoryEntity.TYPE_TRANSACTION, "t2", "bk", HistoryEntity.ACTION_CREATED, at = 3L)) // different entity

        val rows = db.historyDao().observeForEntity(HistoryEntity.TYPE_TRANSACTION, "t1").first()

        assertEquals(listOf("h2", "h1"), rows.map { it.id })
    }

    @Test
    fun `observeForEntity on a BOOK id excludes that book's own entries' history`() = runBlocking {
        db.historyDao().insert(row("h1", HistoryEntity.TYPE_BOOK, "bk", "bk", HistoryEntity.ACTION_CREATED, at = 1L))
        db.historyDao().insert(row("h2", HistoryEntity.TYPE_TRANSACTION, "t1", "bk", HistoryEntity.ACTION_CREATED, at = 2L)) // entry in the same book
        db.historyDao().insert(row("h3", HistoryEntity.TYPE_BOOK, "bk", "bk", HistoryEntity.ACTION_RENAMED, at = 3L))

        val rows = db.historyDao().observeForEntity(HistoryEntity.TYPE_BOOK, "bk").first()

        assertEquals(listOf("h3", "h1"), rows.map { it.id })
    }
}
