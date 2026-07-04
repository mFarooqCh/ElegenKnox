package com.elegen.elegencashbook.data.remote.supabase

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.elegen.elegencashbook.core.logging.Logger
import com.elegen.elegencashbook.core.security.Encryptor
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sessionStore by preferencesDataStore(name = "elegen_session")

/**
 * Supabase session persistence, encrypted at rest (spec §9: Keystore + Tink + DataStore).
 * The JWT never touches disk in plaintext.
 */
@Singleton
class EncryptedSessionStorage @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryptor: Encryptor,
    private val logger: Logger,
) : SessionManager {

    private val key = stringPreferencesKey("session_ciphertext")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun saveSession(session: UserSession) {
        val plaintext = json.encodeToString(UserSession.serializer(), session).toByteArray(Charsets.UTF_8)
        val ciphertext = Base64.encodeToString(encryptor.encrypt(plaintext), Base64.NO_WRAP)
        context.sessionStore.edit { it[key] = ciphertext }
    }

    /** Contract (supabase-kt 3.6): throws when no session; callers use loadSessionOrNull(). */
    override suspend fun loadSession(): UserSession {
        val ciphertext = context.sessionStore.data.first()[key]
            ?: throw IllegalStateException("No session stored")
        try {
            val plaintext = encryptor.decrypt(Base64.decode(ciphertext, Base64.NO_WRAP))
            return json.decodeFromString(UserSession.serializer(), plaintext.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            // Corrupt/undecryptable (e.g. keystore reset) — clear and report absent; never crash startup.
            logger.warn("SessionStorage", "Stored session unreadable; clearing", e)
            deleteSession()
            throw IllegalStateException("Stored session unreadable", e)
        }
    }

    override suspend fun deleteSession() {
        context.sessionStore.edit { it.remove(key) }
    }
}
