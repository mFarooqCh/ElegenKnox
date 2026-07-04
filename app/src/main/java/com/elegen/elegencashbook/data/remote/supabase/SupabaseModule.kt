package com.elegen.elegencashbook.data.remote.supabase

import com.elegen.elegencashbook.BuildConfig
import com.elegen.elegencashbook.core.logging.Logger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import javax.inject.Singleton

/**
 * Nullable holder: no endpoint configured (BuildConfig empty) → client is null and the app
 * runs guest-only. The anon key is public by design; RLS is the security boundary (spec §8.6).
 * The service_role key must never appear in the app (spec §9).
 */
class SupabaseClientHolder(val client: SupabaseClient?) {
    val isConfigured: Boolean get() = client != null
}

/**
 * Must match the intent-filter data scheme/host on MainActivity in AndroidManifest.xml, and must
 * be added to the hosted project's Authentication → URL Configuration → Redirect URLs allow list.
 */
const val AUTH_DEEP_LINK_SCHEME = "elegencashbook"
const val AUTH_DEEP_LINK_HOST = "login-callback"

@Module
@InstallIn(SingletonComponent::class)
object SupabaseModule {

    @Provides
    @Singleton
    fun provideSupabaseClientHolder(
        sessionStorage: EncryptedSessionStorage,
        logger: Logger,
    ): SupabaseClientHolder {
        val url = BuildConfig.SUPABASE_URL
        val key = BuildConfig.SUPABASE_ANON_KEY
        if (url.isBlank() || key.isBlank()) return SupabaseClientHolder(null)
        return try {
            val client = createSupabaseClient(supabaseUrl = url, supabaseKey = key) {
                install(Auth) {
                    sessionManager = sessionStorage
                    alwaysAutoRefresh = true
                    autoLoadFromStorage = true
                    // Email confirmation / magic links open the app directly instead of a bare
                    // localhost redirect page (see AUTH_DEEP_LINK_SCHEME/HOST in MainActivity).
                    scheme = AUTH_DEEP_LINK_SCHEME
                    host = AUTH_DEEP_LINK_HOST
                }
            }
            SupabaseClientHolder(client)
        } catch (e: Exception) {
            // Never let a client-init failure crash launch; degrade to guest-only (offline still works).
            logger.error("Supabase", "Client init failed; running guest-only", e)
            SupabaseClientHolder(null)
        }
    }
}
