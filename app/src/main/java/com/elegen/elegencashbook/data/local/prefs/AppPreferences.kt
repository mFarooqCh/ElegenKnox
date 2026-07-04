package com.elegen.elegencashbook.data.local.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "elegen_prefs")

/** Non-sensitive local prefs. Session/token material goes to the encrypted store in P3, not here. */
@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val keyActiveBusinessId = stringPreferencesKey("active_business_id")
    private val keyDeviceId = stringPreferencesKey("device_id")

    val activeBusinessId: Flow<String?> =
        context.dataStore.data.map { it[keyActiveBusinessId] }

    suspend fun setActiveBusinessId(id: String) {
        context.dataStore.edit { it[keyActiveBusinessId] = id }
    }

    suspend fun clearActiveBusinessId() {
        context.dataStore.edit { it.remove(keyActiveBusinessId) }
    }

    // In-memory only (process lifetime), not DataStore: "continue as guest" should only skip the
    // login prompt for the current app session. Closing and reopening the app must prompt again.
    private val _guestModeChosen = MutableStateFlow(false)
    val guestModeChosen: Flow<Boolean> = _guestModeChosen.asStateFlow()

    fun setGuestModeChosen(chosen: Boolean) {
        _guestModeChosen.value = chosen
    }

    /** Fresh-install state (deviceId regenerates on next use). */
    suspend fun clearAll() {
        cachedDeviceId = null
        _guestModeChosen.value = false
        context.dataStore.edit { it.clear() }
    }

    private val deviceIdMutex = Mutex()
    @Volatile private var cachedDeviceId: String? = null

    /** Delta-pull cursor per entity type (spec §6.4); 0 = never pulled → first pull is a full hydration. */
    suspend fun lastPulledAt(entityType: String): Long =
        context.dataStore.data.first()[longPreferencesKey("last_pulled_$entityType")] ?: 0L

    suspend fun setLastPulledAt(entityType: String, epochMillis: Long) {
        context.dataStore.edit { it[longPreferencesKey("last_pulled_$entityType")] = epochMillis }
    }

    /** Stable per-install id for the sync envelope / LWW tiebreak (spec §6.6). */
    suspend fun deviceId(): String {
        cachedDeviceId?.let { return it }
        return deviceIdMutex.withLock {
            cachedDeviceId?.let { return it }
            val existing = context.dataStore.data.first()[keyDeviceId]
            val id = existing ?: UUID.randomUUID().toString().also { fresh ->
                context.dataStore.edit { it[keyDeviceId] = fresh }
            }
            cachedDeviceId = id
            id
        }
    }
}
