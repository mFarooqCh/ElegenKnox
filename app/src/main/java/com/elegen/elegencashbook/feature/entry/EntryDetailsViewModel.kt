package com.elegen.elegencashbook.feature.entry

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elegen.elegencashbook.core.money.Money
import com.elegen.elegencashbook.domain.model.BookWithBalance
import com.elegen.elegencashbook.domain.model.EntryType
import com.elegen.elegencashbook.domain.model.HistoryEntityType
import com.elegen.elegencashbook.domain.model.HistoryEntry
import com.elegen.elegencashbook.domain.model.Transaction
import com.elegen.elegencashbook.domain.usecase.CopyTransaction
import com.elegen.elegencashbook.domain.usecase.DeleteTransaction
import com.elegen.elegencashbook.domain.usecase.GetEntityHistory
import com.elegen.elegencashbook.domain.usecase.ListBooks
import com.elegen.elegencashbook.domain.usecase.MoveTransaction
import com.elegen.elegencashbook.domain.usecase.ObserveTransaction
import com.elegen.elegencashbook.domain.usecase.UpdateTransaction
import com.elegen.elegencashbook.feature.history.HistoryItem
import com.elegen.elegencashbook.feature.history.toHistoryItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class BookOption(val id: String, val name: String, val entryCount: Int)

data class EntryDetailsUiState(
    val exists: Boolean = true,
    val bookId: String = "",
    val isCashIn: Boolean = true,
    val amountText: String = "",
    val amount: Money = Money.ZERO,
    val description: String = "",
    val entryAtMillis: Long = 0L,
    val entryDateTimeText: String = "",
    val createdLabelText: String = "",
    /** Other books in the same business — move/copy targets. */
    val otherBooks: List<BookOption> = emptyList(),
    /** This entry's own edit history, newest first. */
    val historyItems: List<HistoryItem> = emptyList(),
    val errorMessage: String? = null,
)

sealed interface EntryDetailsUiEvent {
    data class SaveEdit(val amount: Money, val description: String, val entryAt: Long) : EntryDetailsUiEvent
    data object Delete : EntryDetailsUiEvent
    data class Move(val targetBookId: String) : EntryDetailsUiEvent
    data class Copy(val targetBookId: String) : EntryDetailsUiEvent
    data object ErrorShown : EntryDetailsUiEvent
}

@HiltViewModel
class EntryDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    observeTransaction: ObserveTransaction,
    listBooks: ListBooks,
    getEntityHistory: GetEntityHistory,
    private val updateTransaction: UpdateTransaction,
    private val deleteTransaction: DeleteTransaction,
    private val moveTransaction: MoveTransaction,
    private val copyTransaction: CopyTransaction,
) : ViewModel() {

    private val entryId: String = savedStateHandle["entry_id"] ?: ""
    private val businessId: String? = savedStateHandle["business_id"]

    private val dateFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
    private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val historyDateFmt = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault())
    private val _errorMessage = MutableStateFlow<String?>(null)

    val state: StateFlow<EntryDetailsUiState> = combine(
        observeTransaction(entryId),
        businessId?.let { listBooks(it) } ?: flowOf(emptyList()),
        getEntityHistory(HistoryEntityType.TRANSACTION, entryId),
        _errorMessage,
    ) { transaction, books, history, error -> buildState(transaction, books, history, error) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EntryDetailsUiState())

    private fun buildState(transaction: Transaction?, books: List<BookWithBalance>, history: List<HistoryEntry>, errorMessage: String?): EntryDetailsUiState {
        if (transaction == null) return EntryDetailsUiState(exists = false)
        return EntryDetailsUiState(
            exists = true,
            bookId = transaction.bookId,
            isCashIn = transaction.type == EntryType.CASH_IN,
            amountText = transaction.amount.formatGrouped(),
            amount = transaction.amount,
            description = transaction.description,
            entryAtMillis = transaction.createdAt,
            entryDateTimeText = "${dateFmt.format(Date(transaction.createdAt))} · ${timeFmt.format(Date(transaction.createdAt))}",
            createdLabelText = "${dateFmt.format(Date(transaction.updatedAt))} · ${timeFmt.format(Date(transaction.updatedAt))}",
            otherBooks = books
                .filterNot { it.book.id == transaction.bookId }
                .map { BookOption(it.book.id, it.book.name, it.entryCount) },
            historyItems = history.map { it.toHistoryItem(historyDateFmt) },
            errorMessage = errorMessage,
        )
    }

    /** Local writes enforce real capabilities (spec §8.3) now, not just raw ownership — a denied
     * mutation must surface here instead of silently no-op'ing or crashing the app uncaught. */
    private fun runGuarded(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure { e -> _errorMessage.value = e.message }
        }
    }

    fun onEvent(event: EntryDetailsUiEvent) {
        when (event) {
            is EntryDetailsUiEvent.SaveEdit -> runGuarded {
                val current = state.value
                updateTransaction(
                    Transaction(
                        id = entryId,
                        bookId = current.bookId,
                        type = if (current.isCashIn) EntryType.CASH_IN else EntryType.CASH_OUT,
                        amount = event.amount,
                        description = event.description,
                        createdAt = event.entryAt,
                        updatedAt = 0L,
                    )
                )
            }
            EntryDetailsUiEvent.Delete -> runGuarded { deleteTransaction(entryId) }
            is EntryDetailsUiEvent.Move -> runGuarded { moveTransaction(entryId, event.targetBookId) }
            is EntryDetailsUiEvent.Copy -> runGuarded { copyTransaction(entryId, event.targetBookId) }
            EntryDetailsUiEvent.ErrorShown -> _errorMessage.value = null
        }
    }
}
