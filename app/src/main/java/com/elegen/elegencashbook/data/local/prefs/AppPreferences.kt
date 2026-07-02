package com.elegen.elegencashbook.data.local.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
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

    private val deviceIdMutex = Mutex()
    @Volatile private var cachedDeviceId: String? = null

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
