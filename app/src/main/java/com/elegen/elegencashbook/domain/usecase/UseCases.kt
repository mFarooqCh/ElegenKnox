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

private const val MAX_NAME_LENGTH = 64
private const val MAX_DESCRIPTION_LENGTH = 512

class CreateBusiness @Inject constructor(private val repo: BusinessRepository) {
    suspend operator fun invoke(name: String): Business {
        val trimmed = name.trim()
        require(trimmed.isNotBlank()) { "Business name required" }
        require(trimmed.length <= MAX_NAME_LENGTH) { "Business name too long" }
        return repo.create(trimmed)
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
        val trimmed = name.trim()
        require(trimmed.isNotBlank()) { "Book name required" }
        require(trimmed.length <= MAX_NAME_LENGTH) { "Book name too long" }
        return repo.create(businessId, trimmed)
    }
}

class ListBooks @Inject constructor(private val repo: BookRepository) {
    operator fun invoke(businessId: String): Flow<List<BookWithBalance>> =
        repo.observeBooksWithBalance(businessId)
}

class RenameBook @Inject constructor(private val repo: BookRepository) {
    suspend operator fun invoke(bookId: String, name: String) {
        val trimmed = name.trim()
        require(trimmed.isNotBlank()) { "Book name required" }
        require(trimmed.length <= MAX_NAME_LENGTH) { "Book name too long" }
        repo.rename(bookId, trimmed)
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
        val trimmed = description.trim()
        require(trimmed.length <= MAX_DESCRIPTION_LENGTH) { "Description too long" }
        return repo.add(bookId, type, amount, trimmed, createdAt)
    }
}

class UpdateTransaction @Inject constructor(private val repo: TransactionRepository) {
    suspend operator fun invoke(transaction: Transaction) {
        require(transaction.amount.isPositive) { "Amount must be greater than 0" }
        require(transaction.description.length <= MAX_DESCRIPTION_LENGTH) { "Description too long" }
        repo.update(transaction)
    }
}

class DeleteTransaction @Inject constructor(private val repo: TransactionRepository) {
    suspend operator fun invoke(id: String) = repo.softDelete(id)
}

class RestoreTransaction @Inject constructor(private val repo: TransactionRepository) {
    suspend operator fun invoke(id: String) = repo.restore(id)
}

class ObserveTransaction @Inject constructor(private val repo: TransactionRepository) {
    operator fun invoke(id: String): Flow<Transaction?> = repo.observeById(id)
}

class MoveTransaction @Inject constructor(private val repo: TransactionRepository) {
    suspend operator fun invoke(id: String, targetBookId: String) = repo.move(id, targetBookId)
}

class CopyTransaction @Inject constructor(private val repo: TransactionRepository) {
    suspend operator fun invoke(id: String, targetBookId: String): Transaction = repo.copyTo(id, targetBookId)
}

/** Balance math — exact Money arithmetic; overflow throws (constitution §4). */
class GetBalance @Inject constructor() {
    operator fun invoke(entries: List<Transaction>): BalanceSummary {
        val totalIn = entries.filter { it.type == EntryType.CASH_IN }.map { it.amount }.sum()
        val totalOut = entries.filter { it.type == EntryType.CASH_OUT }.map { it.amount }.sum()
        return BalanceSummary(totalIn, totalOut)
    }
}
