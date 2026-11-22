package com.arc.xmanager.api

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Chiffrement des jetons avec une clé du Android Keystore (jamais exportable). */
private object Crypto {
    private const val ALIAS = "arc_tokens"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(enc: String): String? = try {
        val raw = Base64.decode(enc, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        String(c.doFinal(raw, 12, raw.size - 12))
    } catch (e: Exception) {
        null
    }
}

class TokenStore(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("auth", Context.MODE_PRIVATE)

    var clientId: String?
        get() = p.getString("client_id", null)
        set(v) = p.edit { putString("client_id", v) }

    var accessToken: String?
        get() = p.getString("at", null)?.let(Crypto::decrypt)
        set(v) = p.edit { putString("at", v?.let(Crypto::encrypt)) }

    var refreshToken: String?
        get() = p.getString("rt", null)?.let(Crypto::decrypt)
        set(v) = p.edit { putString("rt", v?.let(Crypto::encrypt)) }

    var expiresAt: Long
        get() = p.getLong("exp", 0)
        set(v) = p.edit { putLong("exp", v) }

    var username: String?
        get() = p.getString("username", null)
        set(v) = p.edit { putString("username", v) }

    var pkceVerifier: String?
        get() = p.getString("pkce_verifier", null)
        set(v) = p.edit { putString("pkce_verifier", v) }

    var pkceState: String?
        get() = p.getString("pkce_state", null)
        set(v) = p.edit { putString("pkce_state", v) }

    val isLoggedIn: Boolean get() = p.contains("rt") || p.contains("at")

    fun clearTokens() = p.edit {
        remove("at"); remove("rt"); remove("exp"); remove("username")
        remove("pkce_verifier"); remove("pkce_state")
    }
}

/** État de la file de suppression, affiché dans l'interface. */
class QueueState(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("queue", Context.MODE_PRIVATE)

    var nextAttemptAt: Long
        get() = p.getLong("next", 0)
        set(v) = p.edit { putLong("next", v) }

    var lastError: String?
        get() = p.getString("err", null)
        set(v) = p.edit { putString("err", v) }
}
