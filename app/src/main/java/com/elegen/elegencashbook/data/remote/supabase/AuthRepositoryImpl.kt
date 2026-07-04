package com.elegen.elegencashbook.data.remote.supabase

import com.elegen.elegencashbook.core.logging.Logger
import com.elegen.elegencashbook.domain.model.AuthException
import com.elegen.elegencashbook.domain.model.AuthUser
import com.elegen.elegencashbook.domain.model.SessionState
import com.elegen.elegencashbook.domain.repository.AuthRepository
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val holder: SupabaseClientHolder,
    private val logger: Logger,
) : AuthRepository {

    override val isConfigured: Boolean get() = holder.isConfigured

    override val sessionState: Flow<SessionState> = run {
        val client = holder.client ?: return@run flowOf<SessionState>(SessionState.Guest)
        client.auth.sessionStatus.map { status ->
            when (status) {
                is SessionStatus.Initializing -> SessionState.Loading
                is SessionStatus.Authenticated ->
                    status.session.user?.toDomain()?.let { SessionState.LoggedIn(it) } ?: SessionState.Guest
                is SessionStatus.NotAuthenticated -> SessionState.Guest
                // Refresh failing (offline etc.) is NOT a sign-out — stay logged in with the cached session.
                is SessionStatus.RefreshFailure ->
                    client.auth.currentSessionOrNull()?.user?.toDomain()?.let { SessionState.LoggedIn(it) }
                        ?: SessionState.Guest
            }
        }
    }

    override suspend fun signIn(email: String, password: String) {
        val client = holder.client ?: throw AuthException("Server not configured — continue as guest")
        try {
            client.auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
        } catch (e: RestException) {
            logger.warn("Auth", "Sign-in rejected")
            throw AuthException(signInMessage(e), e)
        } catch (e: HttpRequestException) {
            throw AuthException("Cannot reach server — check your connection", e)
        }
    }

    override suspend fun register(email: String, password: String, displayName: String?, phone: String?): Boolean {
        val client = holder.client ?: throw AuthException("Server not configured — continue as guest")
        try {
            client.auth.signUpWith(Email) {
                this.email = email
                this.password = password
                data = buildJsonObject {
                    displayName?.let { put("display_name", it) }
                    phone?.let { put("phone", it) }
                }
            }
            // Auto-login only when the project has email confirmation disabled (a session exists now).
            return client.auth.currentSessionOrNull() != null
        } catch (e: RestException) {
            logger.warn("Auth", "Registration rejected")
            throw AuthException(registrationMessage(e), e)
        } catch (e: HttpRequestException) {
            throw AuthException("Cannot reach server — check your connection", e)
        }
    }

    override suspend fun signOut() {
        val client = holder.client ?: return
        try {
            client.auth.signOut()
        } catch (e: Exception) {
            // Offline sign-out must still work locally: drop the cached session regardless.
            logger.warn("Auth", "Remote sign-out failed; clearing local session", e)
            client.auth.clearSession()
        }
    }

    private fun signInMessage(e: RestException): String = when {
        e.message?.contains("not confirmed", ignoreCase = true) == true ->
            "Confirm your email first — check your inbox for the link"
        else -> "Sign-in failed: wrong email or password"
    }

    private fun registrationMessage(e: RestException): String = when {
        e.message?.contains("already registered", ignoreCase = true) == true ->
            "This email is already registered — sign in instead"
        e.message?.contains("users_phone_key", ignoreCase = true) == true ||
            e.message?.contains("duplicate", ignoreCase = true) == true ->
            "This email or phone is already in use"
        else -> "Registration failed — please try again"
    }

    private fun UserInfo.toDomain() = AuthUser(
        id = id,
        email = email,
        displayName = userMetadata?.get("display_name")?.jsonPrimitive?.content,
        phone = userMetadata?.get("phone")?.jsonPrimitive?.content,
    )
}
