package com.elegen.elegencashbook.data.di

import android.content.Context
import androidx.room.Room
import com.elegen.elegencashbook.core.common.AppScope
import com.elegen.elegencashbook.core.logging.AndroidLogger
import com.elegen.elegencashbook.core.logging.Logger
import com.elegen.elegencashbook.core.security.Encryptor
import com.elegen.elegencashbook.core.security.TinkEncryptor
import com.elegen.elegencashbook.data.identity.ActiveIdentity
import com.elegen.elegencashbook.data.identity.IdentityManager
import com.elegen.elegencashbook.data.remote.supabase.AuthRepositoryImpl
import com.elegen.elegencashbook.data.repository.LocalDataMaintenanceImpl
import com.elegen.elegencashbook.domain.repository.AuthRepository
import com.elegen.elegencashbook.domain.repository.LocalDataMaintenance
import com.elegen.elegencashbook.data.local.dao.BookDao
import com.elegen.elegencashbook.data.local.dao.BusinessDao
import com.elegen.elegencashbook.data.local.dao.TransactionDao
import com.elegen.elegencashbook.data.local.db.AppDatabase
import com.elegen.elegencashbook.data.repository.BookRepositoryImpl
import com.elegen.elegencashbook.data.repository.BusinessRepositoryImpl
import com.elegen.elegencashbook.data.repository.SettingsRepositoryImpl
import com.elegen.elegencashbook.data.repository.TransactionRepositoryImpl
import com.elegen.elegencashbook.domain.repository.BookRepository
import com.elegen.elegencashbook.domain.repository.BusinessRepository
import com.elegen.elegencashbook.domain.repository.SettingsRepository
import com.elegen.elegencashbook.domain.repository.TransactionRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoroutineModule {

    @Provides
    @Singleton
    @AppScope
    fun provideAppScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "elegen.db").build()

    @Provides fun provideBusinessDao(db: AppDatabase): BusinessDao = db.businessDao()
    @Provides fun provideBookDao(db: AppDatabase): BookDao = db.bookDao()
    @Provides fun provideTransactionDao(db: AppDatabase): TransactionDao = db.transactionDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {

    @Binds @Singleton
    abstract fun bindBusinessRepository(impl: BusinessRepositoryImpl): BusinessRepository

    @Binds @Singleton
    abstract fun bindBookRepository(impl: BookRepositoryImpl): BookRepository

    @Binds @Singleton
    abstract fun bindTransactionRepository(impl: TransactionRepositoryImpl): TransactionRepository

    @Binds @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds @Singleton
    abstract fun bindLogger(impl: AndroidLogger): Logger

    @Binds @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds @Singleton
    abstract fun bindLocalDataMaintenance(impl: LocalDataMaintenanceImpl): LocalDataMaintenance

    @Binds @Singleton
    abstract fun bindEncryptor(impl: TinkEncryptor): Encryptor

    @Binds @Singleton
    abstract fun bindActiveIdentity(impl: IdentityManager): ActiveIdentity
}
