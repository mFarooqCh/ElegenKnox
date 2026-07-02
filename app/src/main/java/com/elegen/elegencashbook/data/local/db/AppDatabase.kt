package com.elegen.elegencashbook.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.elegen.elegencashbook.data.local.dao.BookDao
import com.elegen.elegencashbook.data.local.dao.BusinessDao
import com.elegen.elegencashbook.data.local.dao.TransactionDao
import com.elegen.elegencashbook.data.local.entity.BookEntity
import com.elegen.elegencashbook.data.local.entity.BusinessEntity
import com.elegen.elegencashbook.data.local.entity.CategoryEntity
import com.elegen.elegencashbook.data.local.entity.TransactionEntity

@Database(
    entities = [
        BusinessEntity::class,
        BookEntity::class,
        TransactionEntity::class,
        CategoryEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun businessDao(): BusinessDao
    abstract fun bookDao(): BookDao
    abstract fun transactionDao(): TransactionDao
}
