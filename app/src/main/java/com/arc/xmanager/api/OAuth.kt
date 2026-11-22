package com.arc.xmanager.api

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

/** Connexion OAuth 2.0 avec PKCE : le mot de passe est saisi sur x.com, jamais dans l'app. */
object OAuth {
    const val REDIRECT_URI = "arcx://callback"
    private const val AUTHORIZE_URL = "https://x.com/i/oauth2/authorize"
    private const val SCOPES = "tweet.read tweet.write users.read offline.access"

    private fun randomToken(bytes: Int): String {
        val b = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    fun start(ctx: Context): String? {
        val store = TokenStore(ctx)
        val clientId = store.clientId?.takeIf { it.isNotBlank() } ?: return "Renseigne d'abord ton Client ID."
        val verifier = randomToken(48)
        val state = randomToken(16)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        store.pkceVerifier = verifier
        store.pkceState = state
        val uri = Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
        CustomTabsIntent.Builder().build().launchUrl(ctx, uri)
        return null
    }

    /** Termine la connexion. Retourne null si OK, sinon un message d'erreur. Bloquant. */
    fun finish(ctx: Context, uri: Uri): String? {
        val store = TokenStore(ctx)
        uri.getQueryParameter("error")?.let { return "Connexion refusée ($it)." }
        val code = uri.getQueryParameter("code") ?: return "Réponse de X invalide."
        if (uri.getQueryParameter("state") != store.pkceState) return "Réponse de X invalide (state)."
        val verifier = store.pkceVerifier ?: return "Session de connexion expirée, recommence."
        val clientId = store.clientId ?: return "Client ID manquant."
        return try {
            val (status, json) = XApi.tokenRequest(
                mapOf(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "redirect_uri" to REDIRECT_URI,
                    "client_id" to clientId,
                    "code_verifier" to verifier,
                )
            )
            if (status !in 200..299 || !json.has("access_token")) {
                return "Échec de la connexion : " +
                    json.optString("error_description").ifEmpty { json.optString("error", "HTTP $status") }
            }
            XApi.saveTokens(store, json)
            store.pkceVerifier = null
            store.pkceState = null
            when (val me = XApi(ctx).me()) {
                is ApiResult.Ok -> store.username = me.value
                else -> Unit // non bloquant : le quota de /users/me est faible
            }
            null
        } catch (e: IOException) {
            "Erreur réseau : ${e.message}"
        }
    }
}
