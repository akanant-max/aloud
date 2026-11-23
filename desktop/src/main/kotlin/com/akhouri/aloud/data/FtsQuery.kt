package com.akhouri.aloud.data

/**
 * Turns what someone types into an FTS MATCH expression.
 *
 * Raw input cannot go straight to MATCH: a stray quote or a bare `*` is a syntax error that
 * surfaces as a crash rather than an empty result, and FTS operators like NOT and OR would be
 * read as operators when the user meant the words.
 */
object FtsQuery {

  /**
   * Quoting each token makes it a literal, so punctuation and FTS keywords are searched for
   * rather than interpreted. The last token gets a `*` so results appear while still typing.
   */
  fun sanitise(raw: String): String? {
    val tokens = raw
      .split(TOKEN_SPLIT)
      .filter { it.isNotBlank() }
      .map { it.replace("\"", "") }
      .filter { it.isNotBlank() }

    if (tokens.isEmpty()) return null

    return tokens.mapIndexed { index, token ->
      if (index == tokens.lastIndex) "\"$token\"*" else "\"$token\""
    }.joinToString(" ")
  }

  /**
   * A phrase search: the words have to appear together, in order. Used when the query is already
   * quoted by the user.
   */
  fun phrase(raw: String): String? {
    val cleaned = raw.replace("\"", "").trim()
    return if (cleaned.isEmpty()) null else "\"$cleaned\""
  }

  private val TOKEN_SPLIT = Regex("[^\\p{L}\\p{N}]+")
}
