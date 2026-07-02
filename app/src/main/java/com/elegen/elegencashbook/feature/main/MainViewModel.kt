package com.elegen.elegencashbook.feature.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elegen.elegencashbook.domain.model.BookWithBalance
import com.elegen.elegencashbook.domain.model.BusinessOverview
import com.elegen.elegencashbook.domain.usecase.CreateBook
import com.elegen.elegencashbook.domain.usecase.ListBooks
import com.elegen.elegencashbook.domain.usecase.ListMyBusinesses
import com.elegen.elegencashbook.domain.usecase.ObserveActiveBusinessId
import com.elegen.elegencashbook.domain.usecase.SwitchBusiness
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class BusinessItem(
    val id: String,
    val name: String,
    val roleLabel: String,
    val bookCount: Int,
    val isActive: Boolean,
)

data class BookItem(
    val id: String,
    val name: String,
    val metaText: String,
    val balanceText: String,
)

data class MainUiState(
    val businesses: List<BusinessItem> = emptyList(),
    val activeBusiness: BusinessItem? = null,
    val books: List<BookItem> = emptyList(),
)

sealed interface MainUiEvent {
    data class SelectBusiness(val id: String) : MainUiEvent
    data class AddBook(val name: String) : MainUiEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MainViewModel @Inject constructor(
    listMyBusinesses: ListMyBusinesses,
    observeActiveBusinessId: ObserveActiveBusinessId,
    private val listBooks: ListBooks,
    private val createBook: CreateBook,
    private val switchBusiness: SwitchBusiness,
) : ViewModel() {

    val state: StateFlow<MainUiState> =
        combine(listMyBusinesses(), observeActiveBusinessId()) { businesses, activeId ->
            // Fall back to the first business when nothing was ever selected.
            businesses to (businesses.find { it.business.id == activeId } ?: businesses.firstOrNull())
        }.flatMapLatest { (businesses, active) ->
            val booksFlow = active?.let { listBooks(it.business.id) } ?: flowOf(emptyList())
            booksFlow.map { books -> buildState(businesses, active, books) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    fun onEvent(event: MainUiEvent) {
        when (event) {
            is MainUiEvent.SelectBusiness -> viewModelScope.launch { switchBusiness(event.id) }
            is MainUiEvent.AddBook -> {
                val activeId = state.value.activeBusiness?.id ?: return
                viewModelScope.launch { createBook(activeId, event.name) }
            }
        }
    }

    private fun buildState(
        businesses: List<BusinessOverview>,
        active: BusinessOverview?,
        books: List<BookWithBalance>,
    ): MainUiState {
        val items = businesses.map {
            BusinessItem(
                id = it.business.id,
                name = it.business.name,
                roleLabel = "Owner", // real roles arrive with RBAC (P6)
                bookCount = it.bookCount,
                isActive = it.business.id == active?.business?.id,
            )
        }
        return MainUiState(
            businesses = items,
            activeBusiness = items.find { it.isActive },
            books = books.map {
                BookItem(
                    id = it.book.id,
                    name = it.book.name,
                    metaText = bookMeta(it),
                    balanceText = "Rs ${it.net.format()}",
                )
            },
        )
    }

    private fun bookMeta(book: BookWithBalance): String {
        val last = book.lastEntryAt
        return if (last != null) "Updated ${relativeLabel(last)}" else "Created ${relativeLabel(book.book.createdAt)}"
    }

    private fun relativeLabel(epochMillis: Long): String {
        val days = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - epochMillis)
        return when {
            days <= 0L -> "today"
            days == 1L -> "yesterday"
            days < 7L -> "$days days ago"
            else -> SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(epochMillis))
        }
    }
}
