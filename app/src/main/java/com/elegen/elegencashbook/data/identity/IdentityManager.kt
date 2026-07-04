package com.elegen.elegencashbook.data.identity

import androidx.room.withTransaction
import com.elegen.elegencashbook.core.common.AppScope
import com.elegen.elegencashbook.core.logging.Logger
import com.elegen.elegencashbook.data.local.dao.BookDao
import com.elegen.elegencashbook.data.local.dao.BusinessDao
import com.elegen.elegencashbook.data.local.dao.TransactionDao
import com.elegen.elegencashbook.data.local.db.AppDatabase
import com.elegen.elegencashbook.domain.model.SessionState
import com.elegen.elegencashbook.domain.repository.AuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device's local data-isolation gate (see the "identity-scoped local data" design).
 *
 * Holds the *active identity* — the id that owns the rows currently visible:
 *  - [GUEST_UID] while signed out (guest bucket).
 *  - the real user id while signed in.
 *
 * On login it **claims** the guest bucket into the signed-in user (guest rows → that uid), so
 * data created before signing in becomes part of the account. On logout the active identity
 * flips back to guest: the user's rows stay on disk but are invisible to guest and any other
 * account (retained, not leaked). Explicit "log out & remove data" wipes instead.
 */
@Singleton
class IdentityManager @Inject constructor(
    authRepository: AuthRepository,
    private val db: AppDatabase,
    private val businessDao: BusinessDao,
    private val bookDao: BookDao,
    private val transactionDao: TransactionDao,
    private val logger: Logger,
    @AppScope scope: CoroutineScope,
) {
    private val _activeUid = MutableStateFlow(GUEST_UID)
    val activeUid: StateFlow<String> = _activeUid.asStateFlow()

    init {
        scope.launch {
            // One-time upgrade: rows written by the pre-identity build (ownerUid = "local") become
            // guest-owned, so they stay visible and are claimable on login. Idempotent.
            reassignOwner(LEGACY_UID, GUEST_UID)
            authRepository.sessionState.collect { session ->
                when (session) {
                    is SessionState.LoggedIn -> {
                        // Claim first so the user's list is complete the moment the uid becomes active.
                        reassignOwner(GUEST_UID, session.user.id)
                        _activeUid.value = session.user.id
                    }
                    SessionState.Guest -> _activeUid.value = GUEST_UID
                    SessionState.Loading -> Unit // keep current until resolved
                }
            }
        }
    }

    /** Snapshot for stamping new writes. */
    fun current(): String = _activeUid.value

    private suspend fun reassignOwner(from: String, to: String) {
        if (from == to) return
        val now = System.currentTimeMillis()
        db.withTransaction {
            businessDao.claimOwner(from, to, now)
            bookDao.claimOwner(from, to, now)
            transactionDao.claimCreator(from, to, now)
        }
    }

    companion object {
        /** Owner id for rows created while signed out. */
        const val GUEST_UID = "guest"

        /** Placeholder owner used before identity scoping existed (pre-P3 upgrade path). */
        private const val LEGACY_UID = "local"
    }
}
