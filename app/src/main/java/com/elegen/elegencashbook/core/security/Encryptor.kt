package com.elegen.elegencashbook.core.security

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.inject.Inject
import javax.inject.Singleton

/** Symmetric encryption for local secrets (session tokens). Spec §9: Keystore + Tink + DataStore. */
interface Encryptor {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray): ByteArray
}

/**
 * AES256-GCM AEAD whose keyset is wrapped by an Android Keystore master key.
 * The keyset never exists unencrypted on disk; the master key never leaves Keystore.
 */
@Singleton
class TinkEncryptor @Inject constructor(
    @ApplicationContext private val context: Context,
) : Encryptor {

    private val aead: Aead by lazy {
        AeadConfig.register()
        try {
            buildAead()
        } catch (e: Exception) {
            // Keystore master key invalidated/reset (device security change, keystore wipe) — the
            // wrapped keyset can never be unwrapped again. Same "corrupt, clear it" contract
            // EncryptedSessionStorage already applies to unreadable session data; only recovery
            // is a fresh keyset AND a fresh master key — reusing the same alias without deleting
            // the broken Keystore entry first just fails again with the identical exception.
            context.getSharedPreferences("elegen_keyset_prefs", Context.MODE_PRIVATE).edit().clear().apply()
            runCatching {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("elegen_master_key")
            }
            buildAead()
        }
    }

    private fun buildAead(): Aead =
        AndroidKeysetManager.Builder()
            .withSharedPref(context, "elegen_session_keyset", "elegen_keyset_prefs")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://elegen_master_key")
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)

    override fun encrypt(plaintext: ByteArray): ByteArray = aead.encrypt(plaintext, null)
    override fun decrypt(ciphertext: ByteArray): ByteArray = aead.decrypt(ciphertext, null)
}
