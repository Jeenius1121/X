package com.arc.xmanager.ui

import java.text.DateFormat
import java.util.Date

fun formatDate(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
fun formatTime(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))
fun formatDateTime(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

private val URL = Regex("https?://\\S+")

/** Approximation du compteur de X : un lien compte pour 23 caractères. */
fun xLength(text: String): Int {
    val replaced = URL.replace(text, "x".repeat(23))
    return replaced.codePointCount(0, replaced.length)
}

/** Accepte un lien de tweet (x.com/.../status/123) ou un ID brut. */
fun extractTweetId(input: String): String? {
    val s = input.trim()
    if (s.isEmpty()) return null
    Regex("status(?:es)?/(\\d+)").find(s)?.let { return it.groupValues[1] }
    return s.takeIf { it.all(Char::isDigit) }
}
