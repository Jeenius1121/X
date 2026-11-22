package com.arc.xmanager.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MatcherTest {
    private fun t(id: String, text: String, reply: String? = null, likes: Int = 0, at: Long = 1_000) = Tweet(
        id, text, at, reply, if (reply != null) "bob" else null, text.startsWith("RT @"), likes, 0, "archive"
    )

    private val all = listOf(
        t("1", "J'adore le Football ce soir"),
        t("2", "Le café est prêt", reply = "99"),
        t("3", "RT @alice: footballeur génial"),
        t("4", "Vive le PSG et le foot", likes = 500),
        t("5", "Rien à voir"),
    )

    private fun ids(f: Filter) = all.filter(Matcher.compile(f)).map { it.id }

    @Test fun anyWordsAccentAndCaseInsensitive() {
        assertEquals(listOf("1", "2", "3", "4"), ids(Filter(query = "foot cafe")))
    }

    @Test fun allWords() = assertEquals(listOf("4"), ids(Filter(query = "psg foot", mode = MatchMode.ALL)))

    @Test fun wholeWord() = assertEquals(listOf("4"), ids(Filter(query = "foot", wholeWord = true)))

    @Test fun phraseAndQuotes() {
        assertEquals(listOf("2"), ids(Filter(query = "café est", mode = MatchMode.PHRASE)))
        assertEquals(listOf("2"), ids(Filter(query = "\"cafe est\"")))
    }

    @Test fun excludeAndKind() {
        assertEquals(listOf("1", "4"), ids(Filter(query = "foot", exclude = "génial", kind = Kind.TWEETS)))
        assertEquals(listOf("2"), ids(Filter(kind = Kind.REPLIES)))
        assertEquals(listOf("3"), ids(Filter(kind = Kind.RETWEETS)))
        assertEquals(listOf("2"), ids(Filter(replyToUser = "@Bob")))
    }

    @Test fun likesAndRegex() {
        assertEquals(listOf("1", "3"), ids(Filter(query = "foot", maxLikes = 10)))
        assertEquals(listOf("1", "4"), ids(Filter(query = "\\bfoot(ball)?\\b", mode = MatchMode.REGEX)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun badRegex() { Matcher.compile(Filter(query = "(", mode = MatchMode.REGEX)) }
}
