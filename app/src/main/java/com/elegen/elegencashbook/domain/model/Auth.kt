package com.elegen.elegencashbook.domain.model

/** Authenticated identity (spec §8.2). Pure Kotlin. */
data class AuthUser(
    val id: String,
    val email: String?,
    val displayName: String?,
    val phone: String?,
)

sealed interface SessionState {
    /** Session storage still loading. */
    data object Loading : SessionState

    /** No account in use — app fully functional locally (constitution §1). */
    data object Guest : SessionState

    data class LoggedIn(val user: AuthUser) : SessionState
}

/** Domain-level auth failure with a user-presentable message. */
class AuthException(message: String, cause: Throwable? = null) : Exception(message, cause)
