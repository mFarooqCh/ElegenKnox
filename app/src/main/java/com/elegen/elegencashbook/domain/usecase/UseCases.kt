package com.elegen.elegencashbook.domain.usecase

import com.elegen.elegencashbook.core.money.Money
import com.elegen.elegencashbook.core.money.sum
import com.elegen.elegencashbook.domain.model.BalanceSummary
import com.elegen.elegencashbook.domain.model.Book
import com.elegen.elegencashbook.domain.model.BookWithBalance
import com.elegen.elegencashbook.domain.model.Business
import com.elegen.elegencashbook.domain.model.BusinessOverview
import com.elegen.elegencashbook.domain.model.EntryType
import com.elegen.elegencashbook.domain.model.Transaction
import com.elegen.elegencashbook.domain.repository.BookRepository
import com.elegen.elegencashbook.domain.repository.BusinessRepository
import com.elegen.elegencashbook.domain.repository.SettingsRepository
import com.elegen.elegencashbook.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** Use cases (spec §12): one job each. Constructor-injected repository interfaces only. */

class CreateBusiness @Inject constructor(private val repo: BusinessRepository) {
    suspend operator fun invoke(name: String): Business {
        require(name.isNotBlank()) { "Business name required" }
        return repo.create(name.trim())
    }
}

class ListMyBusinesses @Inject constructor(private val repo: BusinessRepository) {
    operator fun invoke(): Flow<List<BusinessOverview>> = repo.observeBusinesses()
}

class SwitchBusiness @Inject constructor(private val settings: SettingsRepository) {
    suspend operator fun invoke(businessId: String) = settings.setActiveBusinessId(businessId)
}

class ObserveActiveBusinessId @Inject constructor(private val settings: SettingsRepository) {
    operator fun invoke(): Flow<String?> = settings.activeBusinessId
}

class CreateBook @Inject constructor(private val repo: BookRepository) {
    suspend operator fun invoke(businessId: String, name: String): Book {
        require(name.isNotBlank()) { "Book name required" }
        return repo.create(businessId, name.trim())
    }
}

class ListBooks @Inject constructor(private val repo: BookRepository) {
    operator fun invoke(businessId: String): Flow<List<BookWithBalance>> =
        repo.observeBooksWithBalance(businessId)
}

class RenameBook @Inject constructor(private val repo: BookRepository) {
    suspend operator fun invoke(bookId: String, name: String) {
        require(name.isNotBlank()) { "Book name required" }
        repo.rename(bookId, name.trim())
    }
}

class DeleteBook @Inject constructor(private val repo: BookRepository) {
    suspend operator fun invoke(bookId: String) = repo.softDelete(bookId)
}

class RestoreBook @Inject constructor(private val repo: BookRepository) {
    suspend operator fun invoke(bookId: String) = repo.restore(bookId)
}

class DuplicateBook @Inject constructor(private val repo: BookRepository) {
    suspend operator fun invoke(bookId: String): Book = repo.duplicate(bookId)
}

class MoveBook @Inject constructor(private val repo: BookRepository) {
    suspend operator fun invoke(bookId: String, targetBusinessId: String) = repo.move(bookId, targetBusinessId)
}

class ObserveBookEntries @Inject constructor(private val repo: TransactionRepository) {
    operator fun invoke(bookId: String): Flow<List<Transaction>> = repo.observeEntries(bookId)
}

class AddTransaction @Inject constructor(private val repo: TransactionRepository) {
    suspend operator fun invoke(
        bookId: String,
        type: EntryType,
        amount: Money,
        description: String,
        createdAt: Long,
    ): Transaction {
        require(amount.isPositive) { "Amount must be greater than 0" }
        return repo.add(bookId, type, amount, description.trim(), createdAt)
    }
}

class UpdateTransaction @Inject constructor(private val repo: TransactionRepository) {
    suspend operator fun invoke(transaction: Transaction) {
        require(transaction.amount.isPositive) { "Amount must be greater than 0" }
        repo.update(transaction)
    }
}

class DeleteTransaction @Inject constructor(private val repo: TransactionRepository) {
    suspend operator fun invoke(id: String) = repo.softDelete(id)
}

class RestoreTransaction @Inject constructor(private val repo: TransactionRepository) {
    suspend operator fun invoke(id: String) = repo.restore(id)
}

/** Balance math — exact Money arithmetic; overflow throws (constitution §4). */
class GetBalance @Inject constructor() {
    operator fun invoke(entries: List<Transaction>): BalanceSummary {
        val totalIn = entries.filter { it.type == EntryType.CASH_IN }.map { it.amount }.sum()
        val totalOut = entries.filter { it.type == EntryType.CASH_OUT }.map { it.amount }.sum()
        return BalanceSummary(totalIn, totalOut)
    }
}
