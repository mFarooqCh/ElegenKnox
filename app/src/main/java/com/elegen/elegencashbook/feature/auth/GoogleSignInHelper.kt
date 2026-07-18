package com.elegen.elegencashbook.feature.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.security.MessageDigest
import java.util.UUID

/** Wraps Credential Manager's Google ID flow — an Android SDK surface, so it lives in the UI layer, not domain. */
class GoogleSignInHelper(private val context: Context, private val webClientId: String) {

    /** @return (idToken, rawNonce) — rawNonce must be passed to Supabase's signInWith(IDToken) as-is. */
    suspend fun requestIdToken(): Pair<String, String> {
        val rawNonce = UUID.randomUUID().toString()
        val hashedNonce = MessageDigest.getInstance("SHA-256")
            .digest(rawNonce.toByteArray())
            .fold("") { acc, b -> acc + "%02x".format(b) }

        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(webClientId)
            .setNonce(hashedNonce)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

        val result = CredentialManager.create(context).getCredential(context, request)
        val credential = GoogleIdTokenCredential.createFrom(result.credential.data)
        return credential.idToken to rawNonce
    }
}
