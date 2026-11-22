package com.arc.xmanager.data

import java.text.Normalizer

enum class MatchMode(val label: String) {
    ANY("Un des mots"), ALL("Tous les mots"), PHRASE("Phrase exacte"), REGEX("Regex")
}

enum class Kind(val label: String) {
    ALL("Tout"), TWEETS("Tweets"), REPLIES("Réponses"), RETWEETS("Retweets")
}

data class Filter(
    val query: String = "",
    val mode: MatchMode = MatchMode.ANY,
    val exclude: String = "",
    val kind: Kind = Kind.ALL,
    val wholeWord: Boolean = false,
    val caseSensitive: Boolean = false,
    val after: Long? = null,   // début de journée (ms)
    val before: Long? = null,  // début de journée (ms), inclus
    val maxLikes: Int? = null,
    val replyToUser: String = "",
)

/** Recherche locale dans les tweets stockés (archive + tweets publiés via l'app). */
object Matcher {
    private val MARKS = Regex("\\p{Mn}+")
    private val TOKENS = Regex("\"([^\"]+)\"|(\\S+)")
    private const val DAY = 86_400_000L

    /** Découpe en mots ; "entre guillemets" = une expression. */
    fun tokens(s: String): List<String> = TOKENS.findAll(s)
        .map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        .filter { it.isNotBlank() }
        .toList()

    /** Retire les accents et met en minuscules (sauf si sensible à la casse). */
    private fun fold(s: String, caseSensitive: Boolean): String {
        val r = MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "")
        return if (caseSensitive) r else r.lowercase()
    }

    private fun termTest(term: String, whole: Boolean): (String) -> Boolean {
        if (!whole) return { s -> s.contains(term) }
        val re = Regex("(?<![\\p{L}\\p{N}_])" + Regex.escape(term) + "(?![\\p{L}\\p{N}_])")
        return { s -> re.containsMatchIn(s) }
    }

    private fun buildInclude(f: Filter): ((Tweet, String) -> Boolean)? {
        val cs = f.caseSensitive
        if (f.query.isBlank()) return null
        when (f.mode) {
            MatchMode.REGEX -> {
                val re = try {
                    Regex(f.query, if (cs) emptySet() else setOf(RegexOption.IGNORE_CASE))
                } catch (e: Exception) {
                    throw IllegalArgumentException("Regex invalide : " + e.message?.lineSequence()?.firstOrNull())
                }
                return { t, _ -> re.containsMatchIn(t.text) }
            }
            MatchMode.PHRASE -> {
                val test = termTest(fold(f.query.trim(), cs), f.wholeWord)
                return { _, folded -> test(folded) }
            }
            MatchMode.ALL -> {
                val tests = tokens(f.query).map { termTest(fold(it, cs), f.wholeWord) }
                return { _, folded -> tests.all { it(folded) } }
            }
            MatchMode.ANY -> {
                val tests = tokens(f.query).map { termTest(fold(it, cs), f.wholeWord) }
                return { _, folded -> tests.any { it(folded) } }
            }
        }
    }

    /** Construit le prédicat. Lève IllegalArgumentException si la regex est invalide. */
    fun compile(f: Filter): (Tweet) -> Boolean {
        val cs = f.caseSensitive
        val include: ((Tweet, String) -> Boolean)? = buildInclude(f)
        val excludes = tokens(f.exclude).map { termTest(fold(it, cs), f.wholeWord) }
        val replyUser = f.replyToUser.trim().removePrefix("@").lowercase()
        val beforeEnd = f.before?.plus(DAY)

        return pred@{ t ->
            when (f.kind) {
                Kind.ALL -> Unit
                Kind.TWEETS -> if (t.isReply || t.isRetweet) return@pred false
                Kind.REPLIES -> if (!t.isReply) return@pred false
                Kind.RETWEETS -> if (!t.isRetweet) return@pred false
            }
            if (f.after != null && t.createdAt < f.after) return@pred false
            if (beforeEnd != null && t.createdAt >= beforeEnd) return@pred false
            if (f.maxLikes != null && t.likes > f.maxLikes) return@pred false
            if (replyUser.isNotEmpty() && t.replyToUser?.lowercase() != replyUser) return@pred false
            if (include == null && excludes.isEmpty()) return@pred true
            val folded = fold(t.text, cs)
            if (include != null && !include(t, folded)) return@pred false
            excludes.none { it(folded) }
        }
    }
}
