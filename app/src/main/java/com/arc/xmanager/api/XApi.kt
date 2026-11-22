package com.arc.xmanager.api

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    /** Quota de l'API atteint ; on peut réessayer à [resetAt] (ms epoch). */
    data class RateLimited(val resetAt: Long) : ApiResult<Nothing>
    data object NotFound : ApiResult<Nothing>
    data object Unauthorized : ApiResult<Nothing>
    data class Failure(val message: String, val retryable: Boolean) : ApiResult<Nothing>
}

/** Client minimal de l'API officielle X v2 (appels bloquants : à lancer hors du thread UI). */
class XApi(ctx: Context) {
    private val store = TokenStore(ctx)

    companion object {
        private const val BASE = "https://api.x.com/2"
        const val TOKEN_URL = "https://api.x.com/2/oauth2/token"

        private class Resp(val code: Int, val body: String, val headers: Map<String, List<String>>)

        private fun http(method: String, url: String, body: String?, contentType: String?, bearer: String?): Resp {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 20_000
                conn.readTimeout = 30_000
                bearer?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", contentType ?: "application/json")
                    conn.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                return Resp(code, text, conn.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() })
            } finally {
                conn.disconnect()
            }
        }

        /** Requête vers l'endpoint OAuth2 token (client public, PKCE). Lève IOException si réseau. */
        fun tokenRequest(params: Map<String, String>): Pair<Int, JSONObject> {
            val form = params.entries.joinToString("&") {
                "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
            }
            val r = http("POST", TOKEN_URL, form, "application/x-www-form-urlencoded", null)
            val json = runCatching { JSONObject(r.body) }.getOrDefault(JSONObject())
            return r.code to json
        }

        fun saveTokens(store: TokenStore, json: JSONObject) {
            store.accessToken = json.getString("access_token")
            json.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { store.refreshToken = it }
            store.expiresAt = System.currentTimeMillis() + json.optLong("expires_in", 7200) * 1000
        }

        private fun errorMessage(body: String, code: Int): String {
            val j = runCatching { JSONObject(body) }.getOrNull() ?: return "Erreur HTTP $code"
            j.optString("detail").takeIf { it.isNotEmpty() }?.let { return it }
            j.optJSONArray("errors")?.optJSONObject(0)?.let { e ->
                (e.optString("detail").ifEmpty { e.optString("message") }).takeIf { it.isNotEmpty() }?.let { return it }
            }
            return j.optString("title").ifEmpty { "Erreur HTTP $code" }
        }
    }

    val isLoggedIn: Boolean get() = store.isLoggedIn

    /** Rafraîchit le jeton d'accès. null = session invalide. Lève IOException si réseau. */
    @Synchronized
    private fun refresh(): String? {
        val rt = store.refreshToken ?: return null
        val cid = store.clientId ?: return null
        val (code, json) = tokenRequest(
            mapOf("grant_type" to "refresh_token", "refresh_token" to rt, "client_id" to cid)
        )
        if (code in 200..299 && json.has("access_token")) {
            saveTokens(store, json)
            return store.accessToken
        }
        if (code == 400 || code == 401) store.clearTokens()
        return null
    }

    private fun validToken(): String? {
        val at = store.accessToken
        if (at != null && System.currentTimeMillis() < store.expiresAt - 60_000) return at
        return refresh()
    }

    private fun call(method: String, path: String, json: String? = null): ApiResult<JSONObject> = try {
        var token = validToken()
        if (token == null) {
            ApiResult.Unauthorized
        } else {
            var r = http(method, BASE + path, json, "application/json", token)
            if (r.code == 401) {
                token = refresh()
                if (token != null) r = http(method, BASE + path, json, "application/json", token)
            }
            when {
                r.code in 200..299 -> ApiResult.Ok(runCatching { JSONObject(r.body) }.getOrDefault(JSONObject()))
                r.code == 429 -> {
                    val reset = r.headers["x-rate-limit-reset"]?.firstOrNull()?.toLongOrNull()
                    ApiResult.RateLimited(reset?.times(1000) ?: (System.currentTimeMillis() + 15 * 60_000))
                }
                r.code == 404 -> ApiResult.NotFound
                r.code == 401 -> ApiResult.Unauthorized
                r.code >= 500 -> ApiResult.Failure(errorMessage(r.body, r.code), retryable = true)
                else -> ApiResult.Failure(errorMessage(r.body, r.code), retryable = false)
            }
        }
    } catch (e: IOException) {
        ApiResult.Failure("Erreur réseau : ${e.message}", retryable = true)
    }

    /** Nom d'utilisateur du compte connecté. */
    fun me(): ApiResult<String> = when (val r = call("GET", "/users/me")) {
        is ApiResult.Ok -> ApiResult.Ok(r.value.optJSONObject("data")?.optString("username") ?: "")
        is ApiResult.RateLimited -> r
        ApiResult.NotFound -> ApiResult.Failure("Compte introuvable", false)
        ApiResult.Unauthorized -> r as ApiResult.Unauthorized
        is ApiResult.Failure -> r
    }

    /** Publie un tweet (ou une réponse). Retourne l'ID du tweet créé. */
    fun post(text: String, replyTo: String?): ApiResult<String> {
        val body = JSONObject().put("text", text)
        if (!replyTo.isNullOrBlank()) body.put("reply", JSONObject().put("in_reply_to_tweet_id", replyTo))
        return when (val r = call("POST", "/tweets", body.toString())) {
            is ApiResult.Ok -> {
                val id = r.value.optJSONObject("data")?.optString("id").orEmpty()
                if (id.isEmpty()) ApiResult.Failure("Réponse inattendue de X", false) else ApiResult.Ok(id)
            }
            ApiResult.NotFound -> ApiResult.Failure("Tweet de réponse introuvable", false)
            is ApiResult.RateLimited -> r
            ApiResult.Unauthorized -> ApiResult.Unauthorized
            is ApiResult.Failure -> r
        }
    }

    /** Supprime un tweet du compte connecté. */
    fun delete(id: String): ApiResult<Unit> = when (val r = call("DELETE", "/tweets/$id")) {
        is ApiResult.Ok -> {
            val deleted = r.value.optJSONObject("data")?.optBoolean("deleted", false) ?: false
            if (deleted) ApiResult.Ok(Unit)
            else if (r.value.has("errors")) ApiResult.NotFound
            else ApiResult.Failure("X a refusé la suppression", false)
        }
        is ApiResult.RateLimited -> r
        ApiResult.NotFound -> ApiResult.NotFound
        ApiResult.Unauthorized -> ApiResult.Unauthorized
        is ApiResult.Failure -> r
    }
}
