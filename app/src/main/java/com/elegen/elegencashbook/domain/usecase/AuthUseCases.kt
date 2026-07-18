package com.elegen.elegencashbook.domain.usecase

import com.elegen.elegencashbook.domain.model.AuthException
import com.elegen.elegencashbook.domain.model.SessionState
import com.elegen.elegencashbook.domain.repository.AuthRepository
import com.elegen.elegencashbook.domain.repository.LocalDataMaintenance
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** Auth/session use cases (spec §12). */

class ObserveSession @Inject constructor(private val repo: AuthRepository) {
    operator fun invoke(): Flow<SessionState> = repo.sessionState
}

class SignIn @Inject constructor(private val repo: AuthRepository) {
    suspend operator fun invoke(email: String, password: String) {
        val normalized = normalizeEmail(email) ?: throw AuthException("Enter a valid email")
        if (password.isEmpty()) throw AuthException("Enter your password")
        repo.signIn(normalized, password)
    }
}

class RegisterUser @Inject constructor(private val repo: AuthRepository) {
    /** @return true = signed in immediately; false = must confirm email before signing in. */
    suspend operator fun invoke(email: String, password: String, displayName: String?, phone: String?): Boolean {
        val normalizedEmail = normalizeEmail(email) ?: throw AuthException("Enter a valid email")
        if (password.length < 6) throw AuthException("Password must be at least 6 characters")
        val normalizedPhone = phone?.takeIf { it.isNotBlank() }?.let {
            normalizePhone(it) ?: throw AuthException("Enter a valid phone number (e.g. +923001234567)")
        }
        return repo.register(normalizedEmail, password, displayName?.trim()?.takeIf { it.isNotEmpty() }, normalizedPhone)
    }
}

class SignOut @Inject constructor(private val repo: AuthRepository) {
    suspend operator fun invoke() = repo.signOut()
}

class RequestPasswordReset @Inject constructor(private val repo: AuthRepository) {
    suspend operator fun invoke(email: String) {
        val normalized = normalizeEmail(email) ?: throw AuthException("Enter a valid email")
        repo.requestPasswordReset(normalized)
    }
}

class ResetPasswordWithCode @Inject constructor(private val repo: AuthRepository) {
    suspend operator fun invoke(email: String, code: String, newPassword: String) {
        val normalized = normalizeEmail(email) ?: throw AuthException("Enter a valid email")
        if (code.isBlank()) throw AuthException("Enter the code from your email")
        if (newPassword.length < 6) throw AuthException("Password must be at least 6 characters")
        repo.resetPasswordWithCode(normalized, code.trim(), newPassword)
    }
}

class ChangePassword @Inject constructor(private val repo: AuthRepository) {
    suspend operator fun invoke(newPassword: String) {
        if (newPassword.length < 6) throw AuthException("Password must be at least 6 characters")
        repo.updatePassword(newPassword)
    }
}

class SignOutAndWipe @Inject constructor(
    private val repo: AuthRepository,
    private val maintenance: LocalDataMaintenance,
) {
    suspend operator fun invoke() {
        repo.signOut()
        maintenance.wipeAll()
    }
}

/** Lowercased, trimmed; null when not a plausible email (spec §8.1 normalization). */
internal fun normalizeEmail(raw: String): String? {
    val e = raw.trim().lowercase()
    val at = e.indexOf('@')
    if (at < 1 || at != e.lastIndexOf('@')) return null
    val domain = e.substring(at + 1)
    if (domain.isEmpty() || !domain.contains('.') || domain.startsWith(".") || domain.endsWith(".")) return null
    return e
}

/** E.164-ish: strips separators, requires +country and 7–15 digits; null when invalid (spec §8.1). */
internal fun normalizePhone(raw: String): String? {
    val cleaned = raw.filter { it.isDigit() || it == '+' }
    val withPlus = if (cleaned.startsWith("+")) cleaned else "+$cleaned"
    val digits = withPlus.drop(1)
    if (digits.length !in 7..15 || digits.any { !it.isDigit() } || digits.startsWith("0")) return null
    return withPlus
}
