package com.elegen.elegencashbook.data.remote.supabase

import android.content.Intent
import io.github.jan.supabase.auth.handleDeeplinks
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Confines the Supabase SDK's deep-link/session-import mechanics to the data layer — the UI
 * only calls [handle], never touches [SupabaseClientHolder] or the SDK's `handleDeeplinks` directly.
 */
@Singleton
class AuthDeepLinkHandler @Inject constructor(
    private val holder: SupabaseClientHolder,
) {
    /**
     * Consumes [intent]'s data (clears it) if it matches our auth callback, so a later Activity
     * recreation (e.g. rotation) doesn't re-process the same one-time code/token.
     */
    fun handle(intent: Intent, onSuccess: () -> Unit, onError: () -> Unit) {
        val client = holder.client ?: return
        val data = intent.data ?: return
        if (data.scheme != AUTH_DEEP_LINK_SCHEME || data.host != AUTH_DEEP_LINK_HOST) return
        intent.data = null
        client.handleDeeplinks(
            intent = Intent(Intent.ACTION_VIEW, data),
            onSessionSuccess = { onSuccess() },
            onError = { onError() },
        )
    }
}
