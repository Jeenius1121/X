package com.arc.xmanager.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.arc.xmanager.api.ApiResult
import com.arc.xmanager.api.QueueState
import com.arc.xmanager.api.XApi
import com.arc.xmanager.data.Db
import com.arc.xmanager.data.PostStatus
import com.arc.xmanager.data.Tweet
import com.arc.xmanager.data.TweetStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

/**
 * Vide la file de suppression, un tweet à la fois.
 * Quand le quota de l'API est atteint, se reprogramme automatiquement à l'heure de réinitialisation.
 * Continue en arrière-plan, même app fermée.
 */
class DeleteWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private const val NAME = "delete_queue"

        /** Lance la file si elle ne tourne pas déjà (ou n'attend pas déjà la fin d'un quota). */
        fun ensure(ctx: Context) = enqueue(ctx, 0, ExistingWorkPolicy.KEEP)

        fun restartNow(ctx: Context) {
            QueueState(ctx).nextAttemptAt = 0
            enqueue(ctx, 0, ExistingWorkPolicy.REPLACE)
        }

        fun cancel(ctx: Context) = WorkManager.getInstance(ctx).cancelUniqueWork(NAME)

        private fun enqueue(ctx: Context, delayMs: Long, policy: ExistingWorkPolicy) {
            val req = OneTimeWorkRequestBuilder<DeleteWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, policy, req)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = Db.get(applicationContext)
        val api = XApi(applicationContext)
        val state = QueueState(applicationContext)
        if (!api.isLoggedIn) {
            state.lastError = "Connecte ton compte X pour lancer les suppressions."
            return@withContext Result.success()
        }
        while (true) {
            if (isStopped) return@withContext Result.success()
            val id = db.nextQueued() ?: break
            when (val r = api.delete(id)) {
                is ApiResult.Ok -> { db.setStatus(id, TweetStatus.DELETED); state.lastError = null }
                ApiResult.NotFound -> db.setStatus(id, TweetStatus.DELETED) // déjà supprimé
                is ApiResult.RateLimited -> {
                    val wait = (r.resetAt - System.currentTimeMillis()).coerceAtLeast(60_000) + 5_000
                    state.nextAttemptAt = System.currentTimeMillis() + wait
                    state.lastError = null
                    enqueue(applicationContext, wait, ExistingWorkPolicy.REPLACE)
                    return@withContext Result.success()
                }
                ApiResult.Unauthorized -> {
                    state.lastError = "Session X expirée : reconnecte-toi dans Réglages."
                    return@withContext Result.success()
                }
                is ApiResult.Failure -> {
                    if (r.retryable) {
                        state.lastError = r.message
                        return@withContext Result.retry()
                    }
                    db.setStatus(id, TweetStatus.FAILED, r.message)
                }
            }
            delay(1_000)
        }
        state.nextAttemptAt = 0
        Result.success()
    }
}

/** Publie un post programmé à son heure. */
class PostWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private const val KEY_ID = "scheduled_id"

        fun schedule(ctx: Context, id: Long, at: Long) {
            val req = OneTimeWorkRequestBuilder<PostWorker>()
                .setInitialDelay((at - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_ID to id))
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("post_$id", ExistingWorkPolicy.REPLACE, req)
        }

        fun cancel(ctx: Context, id: Long) = WorkManager.getInstance(ctx).cancelUniqueWork("post_$id")
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getLong(KEY_ID, -1)
        val db = Db.get(applicationContext)
        val item = db.getScheduled(id) ?: return@withContext Result.success()
        if (item.status != PostStatus.PENDING) return@withContext Result.success()
        when (val r = XApi(applicationContext).post(item.text, item.replyTo)) {
            is ApiResult.Ok -> {
                db.updateScheduled(id, PostStatus.SENT, tweetId = r.value)
                db.insertTweets(listOf(item.toTweet(r.value)))
            }
            is ApiResult.RateLimited -> schedule(applicationContext, id, r.resetAt + 5_000)
            ApiResult.Unauthorized -> db.updateScheduled(id, PostStatus.FAILED, error = "Session X expirée")
            ApiResult.NotFound -> db.updateScheduled(id, PostStatus.FAILED, error = "Introuvable")
            is ApiResult.Failure ->
                if (r.retryable && runAttemptCount < 5) return@withContext Result.retry()
                else db.updateScheduled(id, PostStatus.FAILED, error = r.message)
        }
        Result.success()
    }
}

fun com.arc.xmanager.data.Scheduled.toTweet(newId: String) = Tweet(
    id = newId, text = text, createdAt = System.currentTimeMillis(),
    replyToId = replyTo, replyToUser = null, isRetweet = false,
    likes = 0, retweets = 0, source = Db.SOURCE_APP,
)
