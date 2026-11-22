package com.arc.xmanager.data

object TweetStatus {
    const val ACTIVE = 0
    const val QUEUED = 1
    const val DELETED = 2
    const val FAILED = 3
}

object PostStatus {
    const val PENDING = 0
    const val SENT = 1
    const val FAILED = 2
}

data class Tweet(
    val id: String,
    val text: String,
    val createdAt: Long,
    val replyToId: String?,
    val replyToUser: String?,
    val isRetweet: Boolean,
    val likes: Int,
    val retweets: Int,
    val source: String,
    val status: Int = TweetStatus.ACTIVE,
    val error: String? = null,
) {
    val isReply: Boolean get() = replyToId != null
}

data class Scheduled(
    val id: Long,
    val text: String,
    val replyTo: String?,
    val at: Long,
    val status: Int,
    val tweetId: String?,
    val error: String?,
)

data class QueueCounts(
    val stored: Int = 0,
    val queued: Int = 0,
    val deleted: Int = 0,
    val failed: Int = 0,
)
