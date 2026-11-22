package com.arc.xmanager.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "arc.db", null, 1) {

    companion object {
        @Volatile private var instance: Db? = null
        fun get(ctx: Context): Db = instance ?: synchronized(this) {
            instance ?: Db(ctx.applicationContext).also { instance = it }
        }
        const val SOURCE_ARCHIVE = "archive"
        const val SOURCE_APP = "app"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE tweets(
                id TEXT PRIMARY KEY,
                text TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                reply_to_id TEXT,
                reply_to_user TEXT,
                is_rt INTEGER NOT NULL DEFAULT 0,
                likes INTEGER NOT NULL DEFAULT 0,
                rts INTEGER NOT NULL DEFAULT 0,
                source TEXT NOT NULL,
                status INTEGER NOT NULL DEFAULT 0,
                error TEXT)"""
        )
        db.execSQL("CREATE INDEX ix_tweets_status ON tweets(status)")
        db.execSQL(
            """CREATE TABLE scheduled(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                text TEXT NOT NULL,
                reply_to TEXT,
                at INTEGER NOT NULL,
                status INTEGER NOT NULL DEFAULT 0,
                tweet_id TEXT,
                error TEXT)"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ---------- Tweets ----------

    /** Insère les tweets inconnus ; ceux déjà présents gardent leur statut. Retourne le nombre ajouté. */
    fun insertTweets(list: List<Tweet>): Int {
        val db = writableDatabase
        var added = 0
        db.beginTransaction()
        try {
            val st = db.compileStatement(
                "INSERT OR IGNORE INTO tweets(id,text,created_at,reply_to_id,reply_to_user,is_rt,likes,rts,source,status) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,0)"
            )
            for (t in list) {
                st.clearBindings()
                st.bindString(1, t.id)
                st.bindString(2, t.text)
                st.bindLong(3, t.createdAt)
                if (t.replyToId != null) st.bindString(4, t.replyToId) else st.bindNull(4)
                if (t.replyToUser != null) st.bindString(5, t.replyToUser) else st.bindNull(5)
                st.bindLong(6, if (t.isRetweet) 1 else 0)
                st.bindLong(7, t.likes.toLong())
                st.bindLong(8, t.retweets.toLong())
                st.bindString(9, t.source)
                if (st.executeInsert() != -1L) added++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return added
    }

    /** Tweets qu'on peut chercher / supprimer (actifs ou en échec). */
    fun searchableTweets(): List<Tweet> {
        val out = ArrayList<Tweet>()
        readableDatabase.rawQuery(
            "SELECT * FROM tweets WHERE status IN (0,3) ORDER BY created_at DESC", null
        ).use { c -> while (c.moveToNext()) out.add(c.toTweet()) }
        return out
    }

    fun markQueued(ids: Collection<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.chunked(500).forEach { chunk ->
                val marks = chunk.joinToString(",") { "?" }
                db.execSQL(
                    "UPDATE tweets SET status=1, error=NULL WHERE id IN ($marks) AND status IN (0,3)",
                    chunk.toTypedArray()
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun nextQueued(): String? = readableDatabase.rawQuery(
        "SELECT id FROM tweets WHERE status=1 ORDER BY created_at DESC LIMIT 1", null
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun setStatus(id: String, status: Int, error: String? = null) {
        writableDatabase.update(
            "tweets",
            ContentValues().apply { put("status", status); put("error", error) },
            "id=?", arrayOf(id)
        )
    }

    fun counts(): QueueCounts {
        var c = QueueCounts()
        readableDatabase.rawQuery("SELECT status, COUNT(*) FROM tweets GROUP BY status", null).use { cur ->
            while (cur.moveToNext()) {
                val n = cur.getInt(1)
                c = when (cur.getInt(0)) {
                    TweetStatus.ACTIVE -> c.copy(stored = c.stored + n)
                    TweetStatus.QUEUED -> c.copy(queued = n, stored = c.stored + n)
                    TweetStatus.DELETED -> c.copy(deleted = n)
                    TweetStatus.FAILED -> c.copy(failed = n, stored = c.stored + n)
                    else -> c
                }
            }
        }
        return c
    }

    fun cancelQueue() = writableDatabase.execSQL("UPDATE tweets SET status=0 WHERE status=1")

    fun requeueFailed() = writableDatabase.execSQL("UPDATE tweets SET status=1, error=NULL WHERE status=3")

    fun clearStoredTweets() = writableDatabase.execSQL("DELETE FROM tweets WHERE status IN (0,2,3)")

    private fun Cursor.toTweet() = Tweet(
        id = getString(getColumnIndexOrThrow("id")),
        text = getString(getColumnIndexOrThrow("text")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        replyToId = getStringOrNull("reply_to_id"),
        replyToUser = getStringOrNull("reply_to_user"),
        isRetweet = getInt(getColumnIndexOrThrow("is_rt")) == 1,
        likes = getInt(getColumnIndexOrThrow("likes")),
        retweets = getInt(getColumnIndexOrThrow("rts")),
        source = getString(getColumnIndexOrThrow("source")),
        status = getInt(getColumnIndexOrThrow("status")),
        error = getStringOrNull("error"),
    )

    private fun Cursor.getStringOrNull(col: String): String? {
        val i = getColumnIndexOrThrow(col)
        return if (isNull(i)) null else getString(i)
    }

    // ---------- Posts programmés ----------

    fun addScheduled(text: String, replyTo: String?, at: Long): Long =
        writableDatabase.insert("scheduled", null, ContentValues().apply {
            put("text", text); put("reply_to", replyTo); put("at", at); put("status", PostStatus.PENDING)
        })

    fun getScheduled(id: Long): Scheduled? =
        readableDatabase.rawQuery("SELECT * FROM scheduled WHERE id=?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) it.toScheduled() else null }

    /** Posts en attente + les 20 derniers traités. */
    fun listScheduled(): List<Scheduled> {
        val out = ArrayList<Scheduled>()
        readableDatabase.rawQuery(
            "SELECT * FROM scheduled WHERE status=0 ORDER BY at ASC", null
        ).use { c -> while (c.moveToNext()) out.add(c.toScheduled()) }
        readableDatabase.rawQuery(
            "SELECT * FROM scheduled WHERE status!=0 ORDER BY at DESC LIMIT 20", null
        ).use { c -> while (c.moveToNext()) out.add(c.toScheduled()) }
        return out
    }

    fun updateScheduled(id: Long, status: Int, tweetId: String? = null, error: String? = null) {
        writableDatabase.update(
            "scheduled",
            ContentValues().apply { put("status", status); put("tweet_id", tweetId); put("error", error) },
            "id=?", arrayOf(id.toString())
        )
    }

    fun deleteScheduled(id: Long) {
        writableDatabase.delete("scheduled", "id=?", arrayOf(id.toString()))
    }

    private fun Cursor.toScheduled() = Scheduled(
        id = getLong(getColumnIndexOrThrow("id")),
        text = getString(getColumnIndexOrThrow("text")),
        replyTo = getStringOrNull("reply_to"),
        at = getLong(getColumnIndexOrThrow("at")),
        status = getInt(getColumnIndexOrThrow("status")),
        tweetId = getStringOrNull("tweet_id"),
        error = getStringOrNull("error"),
    )
}
