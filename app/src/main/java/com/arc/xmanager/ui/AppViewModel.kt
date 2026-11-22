package com.arc.xmanager.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arc.xmanager.api.ApiResult
import com.arc.xmanager.api.OAuth
import com.arc.xmanager.api.QueueState
import com.arc.xmanager.api.TokenStore
import com.arc.xmanager.api.XApi
import com.arc.xmanager.data.ArchiveImporter
import com.arc.xmanager.data.Db
import com.arc.xmanager.data.Filter
import com.arc.xmanager.data.Matcher
import com.arc.xmanager.data.PostStatus
import com.arc.xmanager.data.QueueCounts
import com.arc.xmanager.data.Scheduled
import com.arc.xmanager.data.Tweet
import com.arc.xmanager.work.DeleteWorker
import com.arc.xmanager.work.PostWorker
import com.arc.xmanager.work.toTweet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SearchResult(val items: List<Tweet> = emptyList(), val error: String? = null, val loading: Boolean = true)

data class AccountState(val clientId: String = "", val loggedIn: Boolean = false, val username: String? = null)

data class QueueInfo(val counts: QueueCounts = QueueCounts(), val nextAttemptAt: Long = 0, val lastError: String? = null)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication()
    private val db = Db.get(app)
    private val store = TokenStore(app)
    private val queueState = QueueState(app)

    private val tweets = MutableStateFlow<List<Tweet>>(emptyList())
    val filter = MutableStateFlow(Filter())
    val selection = MutableStateFlow<Set<String>>(emptySet())
    val account = MutableStateFlow(AccountState())
    val queue = MutableStateFlow(QueueInfo())
    val scheduled = MutableStateFlow<List<Scheduled>>(emptyList())
    val importProgress = MutableStateFlow<Int?>(null)
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)

    val results: StateFlow<SearchResult> = combine(tweets, filter) { t, f -> t to f }
        .debounce(200)
        .mapLatest { (list, f) ->
            try {
                val pred = Matcher.compile(f)
                SearchResult(list.filter(pred), loading = false)
            } catch (e: IllegalArgumentException) {
                SearchResult(emptyList(), e.message, loading = false)
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, SearchResult())

    init {
        refreshAccount()
        viewModelScope.launch {
            reloadAll()
            if (queue.value.counts.queued > 0) DeleteWorker.ensure(ctx)
            // Rafraîchit l'avancement des tâches de fond tant que l'app est ouverte.
            while (true) {
                delay(4_000)
                val before = queue.value.counts
                val hasPending = before.queued > 0 || scheduled.value.any { it.status == PostStatus.PENDING }
                if (hasPending) {
                    refreshQueue()
                    reloadScheduled()
                    if (queue.value.counts != before) reloadTweets()
                }
            }
        }
    }

    fun say(msg: String) { message.value = msg }

    private fun refreshAccount() {
        account.value = AccountState(store.clientId.orEmpty(), store.isLoggedIn, store.username)
    }

    private suspend fun reloadTweets() {
        val list = withContext(Dispatchers.IO) { db.searchableTweets() }
        tweets.value = list
        val ids = list.mapTo(HashSet()) { it.id }
        selection.value = selection.value.filterTo(HashSet()) { it in ids }
    }

    private suspend fun refreshQueue() {
        val counts = withContext(Dispatchers.IO) { db.counts() }
        queue.value = QueueInfo(counts, queueState.nextAttemptAt, queueState.lastError)
    }

    private suspend fun reloadScheduled() {
        scheduled.value = withContext(Dispatchers.IO) { db.listScheduled() }
    }

    private suspend fun reloadAll() { reloadTweets(); refreshQueue(); reloadScheduled() }

    // ---------- Compte ----------

    fun saveClientId(id: String) {
        store.clientId = id.trim()
        refreshAccount()
        say("Client ID enregistré.")
    }

    fun connect(activity: Context) {
        OAuth.start(activity)?.let(::say)
    }

    fun handleCallback(uri: Uri) = viewModelScope.launch {
        busy.value = true
        val err = withContext(Dispatchers.IO) { OAuth.finish(ctx, uri) }
        busy.value = false
        refreshAccount()
        say(err ?: "Connecté${store.username?.let { " en tant que @$it" } ?: ""} ✔")
        if (err == null && queue.value.counts.queued > 0) DeleteWorker.restartNow(ctx)
    }

    fun disconnect() {
        store.clearTokens()
        refreshAccount()
        say("Déconnecté.")
    }

    // ---------- Archive ----------

    fun importArchive(uri: Uri) = viewModelScope.launch {
        importProgress.value = 0
        try {
            val r = withContext(Dispatchers.IO) {
                ArchiveImporter.import(ctx, uri) { n -> importProgress.value = n }
            }
            say("${r.read} tweets lus, ${r.added} nouveaux ajoutés.")
        } catch (e: Exception) {
            say("Import impossible : ${e.message}")
        } finally {
            importProgress.value = null
            reloadAll()
        }
    }

    fun clearStored() = viewModelScope.launch {
        withContext(Dispatchers.IO) { db.clearStoredTweets() }
        reloadAll()
        say("Tweets en mémoire effacés (rien n'est supprimé sur X).")
    }

    // ---------- Sélection et suppression ----------

    fun toggle(id: String) {
        selection.value = selection.value.let { if (id in it) it - id else it + id }
    }

    fun selectAllResults() { selection.value = results.value.items.mapTo(HashSet()) { it.id } }
    fun clearSelection() { selection.value = emptySet() }

    fun deleteSelected() = viewModelScope.launch {
        val ids = selection.value
        if (ids.isEmpty()) return@launch
        withContext(Dispatchers.IO) { db.markQueued(ids) }
        selection.value = emptySet()
        if (!store.isLoggedIn) say("${ids.size} tweets en file. Connecte ton compte pour lancer la suppression.")
        else say("${ids.size} tweets ajoutés à la file de suppression.")
        DeleteWorker.ensure(ctx)
        reloadAll()
    }

    fun cancelQueue() = viewModelScope.launch {
        DeleteWorker.cancel(ctx)
        withContext(Dispatchers.IO) { db.cancelQueue() }
        queueState.nextAttemptAt = 0
        reloadAll()
        say("File de suppression annulée.")
    }

    fun retryFailed() = viewModelScope.launch {
        withContext(Dispatchers.IO) { db.requeueFailed() }
        DeleteWorker.ensure(ctx)
        reloadAll()
    }

    fun forceRetryNow() {
        DeleteWorker.restartNow(ctx)
        viewModelScope.launch { refreshQueue() }
    }

    // ---------- Publication ----------

    fun publishNow(text: String, replyTo: String?, onDone: () -> Unit) = viewModelScope.launch {
        busy.value = true
        val r = withContext(Dispatchers.IO) { XApi(ctx).post(text, replyTo) }
        busy.value = false
        when (r) {
            is ApiResult.Ok -> {
                withContext(Dispatchers.IO) {
                    db.insertTweets(listOf(Scheduled(0, text, replyTo, 0, 0, null, null).toTweet(r.value)))
                }
                reloadAll()
                say("Publié ✔")
                onDone()
            }
            is ApiResult.RateLimited -> say(
                "Quota de publication atteint. Réessaie après ${formatTime(r.resetAt)} ou programme le post."
            )
            ApiResult.Unauthorized -> { refreshAccount(); say("Connecte ton compte X dans Réglages.") }
            ApiResult.NotFound -> say("Tweet introuvable.")
            is ApiResult.Failure -> say(r.message)
        }
    }

    fun schedule(text: String, replyTo: String?, at: Long, onDone: () -> Unit) = viewModelScope.launch {
        val id = withContext(Dispatchers.IO) { db.addScheduled(text, replyTo, at) }
        PostWorker.schedule(ctx, id, at)
        reloadScheduled()
        say("Programmé pour ${formatDateTime(at)}.")
        onDone()
    }

    fun cancelScheduled(id: Long) = viewModelScope.launch {
        PostWorker.cancel(ctx, id)
        withContext(Dispatchers.IO) { db.deleteScheduled(id) }
        reloadScheduled()
    }
}
