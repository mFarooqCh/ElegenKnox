package com.elegen.elegencashbook.feature.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elegen.elegencashbook.domain.model.BookWithBalance
import com.elegen.elegencashbook.domain.model.BusinessOverview
import com.elegen.elegencashbook.domain.model.SessionState
import com.elegen.elegencashbook.domain.repository.AuthRepository
import com.elegen.elegencashbook.domain.repository.SettingsRepository
import com.elegen.elegencashbook.domain.usecase.CreateBook
import com.elegen.elegencashbook.domain.usecase.DeleteBook
import com.elegen.elegencashbook.domain.usecase.DuplicateBook
import com.elegen.elegencashbook.core.permission.BusinessRole
import com.elegen.elegencashbook.domain.usecase.GetMyBusinessRole
import com.elegen.elegencashbook.domain.usecase.ListBooks
import com.elegen.elegencashbook.domain.usecase.ListMyBusinesses
import com.elegen.elegencashbook.domain.usecase.MoveBook
import com.elegen.elegencashbook.domain.usecase.ObserveActiveBusinessId
import com.elegen.elegencashbook.domain.usecase.ObserveSession
import com.elegen.elegencashbook.domain.usecase.RenameBook
import com.elegen.elegencashbook.domain.usecase.RestoreBook
import com.elegen.elegencashbook.domain.usecase.SignOut
import com.elegen.elegencashbook.domain.usecase.SignOutAndWipe
import com.elegen.elegencashbook.domain.usecase.SwitchBusiness
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
    val isOwner: Boolean,
    val bookCount: Int,
    val isActive: Boolean,
)

data class BookItem(
    val id: String,
    val name: String,
    val metaText: String,
    val balanceText: String,
    val balanceIsNegative: Boolean,
    val entryCount: Int,
)

data class AccountUi(
    val loggedIn: Boolean,
    /** Display name / email / "Guest" — short label for the top bar. */
    val label: String,
    val email: String? = null,
    val phone: String? = null,
)

fun SessionState.toAccountUi(): AccountUi = when (this) {
    is SessionState.LoggedIn -> AccountUi(
        loggedIn = true,
        label = user.displayName ?: user.email ?: "Account",
        email = user.email,
        phone = user.phone,
    )
    else -> AccountUi(loggedIn = false, label = "Guest")
}

data class MainUiState(
    val businesses: List<BusinessItem> = emptyList(),
    val activeBusiness: BusinessItem? = null,
    val books: List<BookItem> = emptyList(),
    val account: AccountUi = AccountUi(loggedIn = false, label = "Guest"),
    /** First launch, no auth choice made yet, server configured → UI shows the login screen once. */
    val promptLogin: Boolean = false,
    val errorMessage: String? = null,
)

sealed interface MainUiEvent {
    data class SelectBusiness(val id: String) : MainUiEvent
    data class AddBook(val name: String) : MainUiEvent
    data class RenameBook(val id: String, val name: String) : MainUiEvent
    data class DeleteBook(val id: String) : MainUiEvent
    data class RestoreBook(val id: String) : MainUiEvent
    data class DuplicateBook(val id: String) : MainUiEvent
    data class MoveBook(val id: String, val targetBusinessId: String) : MainUiEvent
    /** Keeps local data (spec §8.2). */
    data object SignOutKeepData : MainUiEvent
    /** Wipes Room + prefs back to fresh install. */
    data object SignOutWipeData : MainUiEvent
    data object ErrorShown : MainUiEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MainViewModel @Inject constructor(
    listMyBusinesses: ListMyBusinesses,
    observeActiveBusinessId: ObserveActiveBusinessId,
    observeSession: ObserveSession,
    authRepository: AuthRepository,
    private val listBooks: ListBooks,
    private val createBook: CreateBook,
    private val renameBook: RenameBook,
    private val deleteBook: DeleteBook,
    private val restoreBook: RestoreBook,
    private val duplicateBook: DuplicateBook,
    private val moveBook: MoveBook,
    private val getMyBusinessRole: GetMyBusinessRole,
    private val switchBusiness: SwitchBusiness,
    private val signOut: SignOut,
    private val signOutAndWipe: SignOutAndWipe,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val serverConfigured = authRepository.isConfigured
    private val _errorMessage = MutableStateFlow<String?>(null)

    val state: StateFlow<MainUiState> =
        combine(
            listMyBusinesses(),
            observeActiveBusinessId(),
            observeSession(),
            settings.guestModeChosen,
            _errorMessage,
        ) { businesses, activeId, session, guestChosen, error ->
            // Fall back to the first business when nothing was ever selected.
            val active = businesses.find { it.business.id == activeId } ?: businesses.firstOrNull()
            Inputs(businesses, active, session, guestChosen, error)
        }.flatMapLatest { inputs ->
            val booksFlow = inputs.active?.let { listBooks(it.business.id) } ?: flowOf(emptyList())
            booksFlow.map { books -> buildState(inputs, books) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    /** Local writes now enforce real capabilities (spec §8.3), not just raw ownership — a denied
     * mutation must surface here instead of silently no-op'ing or crashing the app uncaught. */
    private fun runGuarded(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure { e -> _errorMessage.value = e.message }
        }
    }

    fun onEvent(event: MainUiEvent) {
        when (event) {
            is MainUiEvent.SelectBusiness -> viewModelScope.launch { switchBusiness(event.id) }
            is MainUiEvent.AddBook -> {
                val activeId = state.value.activeBusiness?.id ?: return
                runGuarded { createBook(activeId, event.name) }
            }
            is MainUiEvent.RenameBook -> runGuarded { renameBook(event.id, event.name) }
            is MainUiEvent.DeleteBook -> runGuarded { deleteBook(event.id) }
            is MainUiEvent.RestoreBook -> runGuarded { restoreBook(event.id) }
            is MainUiEvent.DuplicateBook -> runGuarded { duplicateBook(event.id) }
            is MainUiEvent.MoveBook -> runGuarded { moveBook(event.id, event.targetBusinessId) }
            MainUiEvent.SignOutKeepData -> viewModelScope.launch {
                signOut()
                settings.clearActiveBusinessId()
                settings.setGuestModeChosen(true) // stay in the app as guest, no auto-prompt
            }
            MainUiEvent.SignOutWipeData -> viewModelScope.launch {
                signOutAndWipe()
            }
            MainUiEvent.ErrorShown -> _errorMessage.value = null
        }
    }

    private data class Inputs(
        val businesses: List<BusinessOverview>,
        val active: BusinessOverview?,
        val session: SessionState,
        val guestChosen: Boolean,
        val errorMessage: String?,
    )

    private suspend fun buildState(inputs: Inputs, books: List<BookWithBalance>): MainUiState {
        return buildListState(inputs.businesses, inputs.active, books).copy(
            account = inputs.session.toAccountUi(),
            promptLogin = serverConfigured &&
                inputs.session is SessionState.Guest &&
                !inputs.guestChosen,
            errorMessage = inputs.errorMessage,
        )
    }

    private suspend fun buildListState(
        businesses: List<BusinessOverview>,
        active: BusinessOverview?,
        books: List<BookWithBalance>,
    ): MainUiState {
        val items = businesses.map {
            val role = getMyBusinessRole(it.business.id)
            BusinessItem(
                id = it.business.id,
                name = it.business.name,
                // Local-mirror lookup, display only — blank rather than a wrong guess while it lags.
                roleLabel = role?.name?.lowercase()?.replaceFirstChar { c -> c.uppercase() } ?: "",
                isOwner = role == BusinessRole.OWNER,
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
                    balanceText = "Rs ${it.net.abs.formatCompact()}",
                    balanceIsNegative = it.net.isNegative,
                    entryCount = it.entryCount,
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
