package com.piotr.phonemcp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The lock-screen PIN/password for the `unlock` tool, encrypted with an Android Keystore
 * key. After one failed attempt it is paused until the user saves it again.
 */
object UnlockSecret {
    private const val KEY_ALIAS = "phone_mcp_unlock"
    private const val PREFS = "phone_mcp"
    private const val KEY_SECRET = "unlock_secret"
    private const val KEY_PAUSED = "unlock_paused"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        // Deliberately usable while the device is locked: that is when unlock needs it.
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    fun save(context: Context, secret: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        val stored = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs(context).edit().putString(KEY_SECRET, stored).putBoolean(KEY_PAUSED, false).apply()
    }

    fun load(context: Context): String? {
        val stored = prefs(context).getString(KEY_SECRET, null) ?: return null
        val (iv, encrypted) = stored.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        }
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    fun isSet(context: Context) = prefs(context).contains(KEY_SECRET)

    fun isPaused(context: Context) = prefs(context).getBoolean(KEY_PAUSED, false)

    fun pause(context: Context) {
        prefs(context).edit().putBoolean(KEY_PAUSED, true).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_SECRET).remove(KEY_PAUSED).apply()
    }
}
