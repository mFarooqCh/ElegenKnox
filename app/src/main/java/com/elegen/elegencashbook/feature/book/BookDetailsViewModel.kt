package com.elegen.elegencashbook.feature.book

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elegen.elegencashbook.core.money.Money
import com.elegen.elegencashbook.domain.model.BusinessOverview
import com.elegen.elegencashbook.domain.model.EntryType
import com.elegen.elegencashbook.domain.model.HistoryEntityType
import com.elegen.elegencashbook.domain.model.HistoryEntry
import com.elegen.elegencashbook.domain.model.Transaction
import com.elegen.elegencashbook.domain.usecase.AddTransaction
import com.elegen.elegencashbook.domain.usecase.DeleteBook
import com.elegen.elegencashbook.domain.usecase.DeleteTransaction
import com.elegen.elegencashbook.domain.usecase.DuplicateBook
import com.elegen.elegencashbook.domain.usecase.GetBalance
import com.elegen.elegencashbook.domain.usecase.GetEntityHistory
import com.elegen.elegencashbook.domain.usecase.ListMyBusinesses
import com.elegen.elegencashbook.domain.usecase.MoveBook
import com.elegen.elegencashbook.domain.usecase.ObserveBookEntries
import com.elegen.elegencashbook.domain.usecase.RenameBook
import com.elegen.elegencashbook.domain.usecase.RestoreBook
import com.elegen.elegencashbook.domain.usecase.RestoreTransaction
import com.elegen.elegencashbook.feature.history.HistoryItem
import com.elegen.elegencashbook.feature.history.toHistoryItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class EntryItem(
    val id: String,
    val title: String,
    val isCashIn: Boolean,
    val amountText: String,
    val runningBalanceText: String,
    val timeText: String,
    val dateText: String,
)

data class BusinessOption(val id: String, val name: String, val bookCount: Int)

data class BookDetailsUiState(
    /** Newest first for display. */
    val entries: List<EntryItem> = emptyList(),
    val totalInText: String = "0",
    val totalOutText: String = "0",
    val netText: String = "0",
    val netIsNegative: Boolean = false,
    val entryCount: Int = 0,
    /** Move-book targets: businesses other than the one this book currently lives in. */
    val otherBusinesses: List<BusinessOption> = emptyList(),
    /** Book-level changes only (rename/move/delete/restore/create) — not its entries', newest first. */
    val historyItems: List<HistoryItem> = emptyList(),
)

sealed interface BookDetailsUiEvent {
    data class SaveEntry(
        val amount: Money,
        val description: String,
        val isCashIn: Boolean,
        val entryAt: Long,
    ) : BookDetailsUiEvent

    data class DeleteEntry(val id: String) : BookDetailsUiEvent
    data class RestoreEntry(val id: String) : BookDetailsUiEvent

    data class RenameBook(val name: String) : BookDetailsUiEvent
    data object DuplicateBook : BookDetailsUiEvent
    data object DeleteBook : BookDetailsUiEvent
    data object RestoreBook : BookDetailsUiEvent
    data class MoveBook(val targetBusinessId: String) : BookDetailsUiEvent
}

@HiltViewModel
class BookDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    observeBookEntries: ObserveBookEntries,
    listMyBusinesses: ListMyBusinesses,
    getEntityHistory: GetEntityHistory,
    private val getBalance: GetBalance,
    private val addTransaction: AddTransaction,
    private val deleteTransaction: DeleteTransaction,
    private val restoreTransaction: RestoreTransaction,
    private val renameBook: RenameBook,
    private val deleteBook: DeleteBook,
    private val restoreBook: RestoreBook,
    private val duplicateBook: DuplicateBook,
    private val moveBook: MoveBook,
) : ViewModel() {

    /** Populated from the launching intent's extras. */
    private val bookId: String = savedStateHandle["book_id"] ?: ""
    private val businessId: String? = savedStateHandle["business_id"]

    private val dateFmt = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    private val timeFmt = SimpleDateFormat("hh:mm a", Locale.getDefault())
    private val historyDateFmt = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault())

    val state: StateFlow<BookDetailsUiState> = combine(
        observeBookEntries(bookId),
        listMyBusinesses(),
        getEntityHistory(HistoryEntityType.BOOK, bookId),
    ) { entries, businesses, history -> buildState(entries, businesses, history) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookDetailsUiState())

    fun onEvent(event: BookDetailsUiEvent) {
        when (event) {
            is BookDetailsUiEvent.SaveEntry -> viewModelScope.launch {
                addTransaction(
                    bookId = bookId,
                    type = if (event.isCashIn) EntryType.CASH_IN else EntryType.CASH_OUT,
                    amount = event.amount,
                    description = event.description,
                    createdAt = event.entryAt,
                )
            }
            is BookDetailsUiEvent.DeleteEntry -> viewModelScope.launch { deleteTransaction(event.id) }
            is BookDetailsUiEvent.RestoreEntry -> viewModelScope.launch { restoreTransaction(event.id) }
            is BookDetailsUiEvent.RenameBook -> viewModelScope.launch { renameBook(bookId, event.name) }
            BookDetailsUiEvent.DuplicateBook -> viewModelScope.launch { duplicateBook(bookId) }
            BookDetailsUiEvent.DeleteBook -> viewModelScope.launch { deleteBook(bookId) }
            BookDetailsUiEvent.RestoreBook -> viewModelScope.launch { restoreBook(bookId) }
            is BookDetailsUiEvent.MoveBook -> viewModelScope.launch { moveBook(bookId, event.targetBusinessId) }
        }
    }

    /** [entries] arrive chronological (oldest first) from the repository. */
    private fun buildState(entries: List<Transaction>, businesses: List<BusinessOverview>, history: List<HistoryEntry>): BookDetailsUiState {
        val summary = getBalance(entries)
        var running = Money.ZERO
        val itemsChronological = entries.map { entry ->
            running += if (entry.type == EntryType.CASH_IN) entry.amount else -entry.amount
            EntryItem(
                id = entry.id,
                title = entry.description.ifEmpty {
                    if (entry.type == EntryType.CASH_IN) "Cash In" else "Cash Out"
                },
                isCashIn = entry.type == EntryType.CASH_IN,
                amountText = entry.amount.format(),
                runningBalanceText = running.format(),
                timeText = timeFmt.format(Date(entry.createdAt)),
                dateText = dateFmt.format(Date(entry.createdAt)),
            )
        }
        return BookDetailsUiState(
            entries = itemsChronological.asReversed(),
            totalInText = summary.totalIn.format(),
            totalOutText = summary.totalOut.format(),
            netText = summary.net.abs.format(),
            netIsNegative = summary.net.isNegative,
            entryCount = entries.size,
            otherBusinesses = businesses
                .filterNot { it.business.id == businessId }
                .map { BusinessOption(it.business.id, it.business.name, it.bookCount) },
            historyItems = history.map { it.toHistoryItem(historyDateFmt) },
        )
    }
}
