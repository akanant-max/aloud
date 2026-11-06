package com.akhouri.aloud.importer

import android.text.Html

/**
 * Turns a chapter of XHTML into paragraphs.
 *
 * Android's own HTML parser does the heavy lifting: it survives the malformed markup that real
 * EPUBs are full of, and it decodes entities correctly. Scripts, styles and comments are cut
 * first, because the platform parser keeps the text inside tags it does not recognise and that
 * text would otherwise be narrated.
 */
object HtmlText {

  private val DROP_BLOCKS = Regex(
    "<(script|style|head|svg|template)\\b[^>]*>.*?</\\1\\s*>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
  )
  private val COMMENTS = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

  /** Block tags that should end a paragraph, in case the source only uses <br> or <div>. */
  private val BLOCK_BREAKS = Regex(
    "</?(p|div|br|li|h[1-6]|tr|blockquote|section|article)\\b[^>]*>",
    RegexOption.IGNORE_CASE,
  )

  fun toParagraphs(html: String): List<String> {
    val cleaned = html
      .replace(COMMENTS, " ")
      .replace(DROP_BLOCKS, " ")
      .replace(BLOCK_BREAKS) { match -> match.value + PARAGRAPH_MARK }

    @Suppress("DEPRECATION")
    val spanned = Html.fromHtml(cleaned, Html.FROM_HTML_MODE_COMPACT)

    return spanned.toString()
      .split(PARAGRAPH_MARK, "\n")
      .map(TextNormaliser::paragraph)
      .filter { it.isNotEmpty() }
  }

  /** The first heading in a chapter, used as its title when the table of contents has none. */
  fun firstHeading(html: String): String? {
    val match = HEADING.find(html) ?: return null
    val inner = match.groupValues.getOrNull(2) ?: return null
    @Suppress("DEPRECATION")
    val text = Html.fromHtml(inner, Html.FROM_HTML_MODE_COMPACT).toString()
    return TextNormaliser.paragraph(text).takeIf { it.isNotEmpty() && it.length <= 120 }
  }

  private val HEADING = Regex(
    "<(h[1-6])\\b[^>]*>(.*?)</\\1\\s*>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
  )

  private const val PARAGRAPH_MARK = "\u0001"
}
