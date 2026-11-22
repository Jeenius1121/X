package com.arc.xmanager.data

import android.content.Context
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Importe l'archive officielle de X (Paramètres > Votre compte > Télécharger une archive).
 * Accepte le .zip complet ou directement le fichier data/tweets.js.
 */
object ArchiveImporter {
    private val TWEETS_FILE = Regex("""(^|/)data/tweets?(-part\d+)?\.js$""")
    private val dateFormat = SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy", Locale.US)

    class Result(val read: Int, val added: Int)

    fun import(ctx: Context, uri: Uri, onProgress: (Int) -> Unit): Result {
        val db = Db.get(ctx)
        var read = 0
        var added = 0
        val batch = ArrayList<Tweet>(500)
        fun flush() {
            added += db.insertTweets(batch)
            batch.clear()
            onProgress(read)
        }
        val sink: (Tweet) -> Unit = { t ->
            batch.add(t); read++
            if (batch.size >= 500) flush()
        }

        val input = ctx.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Impossible d'ouvrir le fichier")
        BufferedInputStream(input).use { stream ->
            stream.mark(4)
            val isZip = stream.read() == 'P'.code && stream.read() == 'K'.code
            stream.reset()
            if (isZip) {
                val zip = ZipInputStream(stream)
                var found = false
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && TWEETS_FILE.containsMatchIn(entry.name)) {
                        found = true
                        parse(InputStreamReader(zip, Charsets.UTF_8), sink)
                    }
                }
                if (!found) throw IllegalArgumentException("Aucun fichier data/tweets.js dans cette archive")
            } else {
                parse(InputStreamReader(stream, Charsets.UTF_8), sink)
            }
        }
        flush()
        return Result(read, added)
    }

    /** Le fichier commence par "window.YTD.tweets.part0 = [" : on saute jusqu'au "=". */
    private fun parse(reader: Reader, sink: (Tweet) -> Unit) {
        val buffered = reader.buffered()
        buffered.mark(1024)
        var c = buffered.read()
        var n = 0
        while (c != -1 && c != '='.code && c != '['.code && n < 256) { c = buffered.read(); n++ }
        if (c == '['.code || c != '='.code) buffered.reset()

        val json = JsonReader(buffered)
        json.isLenient = true
        json.beginArray()
        while (json.hasNext()) {
            json.beginObject()
            while (json.hasNext()) {
                if (json.nextName() == "tweet") readTweet(json)?.let(sink) else json.skipValue()
            }
            json.endObject()
        }
        json.endArray()
    }

    private fun JsonReader.str(): String? =
        if (peek() == JsonToken.NULL) { nextNull(); null } else nextString()

    private fun readTweet(json: JsonReader): Tweet? {
        var id: String? = null
        var text = ""
        var created = 0L
        var replyId: String? = null
        var replyUser: String? = null
        var likes = 0
        var rts = 0
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "id_str" -> id = json.str()
                "full_text" -> text = json.str().orEmpty()
                "created_at" -> created = json.str()?.let {
                    runCatching { synchronized(dateFormat) { dateFormat.parse(it)?.time } }.getOrNull()
                } ?: 0L
                "in_reply_to_status_id_str" -> replyId = json.str()
                "in_reply_to_screen_name" -> replyUser = json.str()
                "favorite_count" -> likes = json.str()?.toIntOrNull() ?: 0
                "retweet_count" -> rts = json.str()?.toIntOrNull() ?: 0
                else -> json.skipValue()
            }
        }
        json.endObject()
        if (id == null) return null
        val clean = text.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        return Tweet(
            id = id, text = clean, createdAt = created,
            replyToId = replyId?.takeIf { it.isNotEmpty() }, replyToUser = replyUser,
            isRetweet = clean.startsWith("RT @"), likes = likes, retweets = rts,
            source = Db.SOURCE_ARCHIVE,
        )
    }
}
