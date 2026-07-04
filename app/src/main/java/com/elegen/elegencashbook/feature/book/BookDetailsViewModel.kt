package com.elegen.elegencashbook.feature.book

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elegen.elegencashbook.core.money.Money
import com.elegen.elegencashbook.domain.model.BusinessOverview
import com.elegen.elegencashbook.domain.model.EntryType
import com.elegen.elegencashbook.domain.model.Transaction
import com.elegen.elegencashbook.domain.usecase.AddTransaction
import com.elegen.elegencashbook.domain.usecase.DeleteBook
import com.elegen.elegencashbook.domain.usecase.DeleteTransaction
import com.elegen.elegencashbook.domain.usecase.DuplicateBook
import com.elegen.elegencashbook.domain.usecase.GetBalance
import com.elegen.elegencashbook.domain.usecase.ListMyBusinesses
import com.elegen.elegencashbook.domain.usecase.MoveBook
import com.elegen.elegencashbook.domain.usecase.ObserveBookEntries
import com.elegen.elegencashbook.domain.usecase.RenameBook
import com.elegen.elegencashbook.domain.usecase.RestoreBook
import com.elegen.elegencashbook.domain.usecase.RestoreTransaction
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

data class BusinessOption(val id: String, val name: String)

data class BookDetailsUiState(
    /** Newest first for display. */
    val entries: List<EntryItem> = emptyList(),
    val totalInText: String = "0",
    val totalOutText: String = "0",
    val netText: String = "0",
    val entryCount: Int = 0,
    /** Move-book targets: businesses other than the one this book currently lives in. */
    val otherBusinesses: List<BusinessOption> = emptyList(),
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

    val state: StateFlow<BookDetailsUiState> = combine(
        observeBookEntries(bookId),
        listMyBusinesses(),
    ) { entries, businesses -> buildState(entries, businesses) }
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
    private fun buildState(entries: List<Transaction>, businesses: List<BusinessOverview>): BookDetailsUiState {
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
            netText = summary.net.format(),
            entryCount = entries.size,
            otherBusinesses = businesses
                .filterNot { it.business.id == businessId }
                .map { BusinessOption(it.business.id, it.business.name) },
        )
    }
}
