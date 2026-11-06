package com.akhouri.aloud.importer

import android.content.Context
import android.net.Uri
import com.akhouri.aloud.data.entity.BookFormat

/**
 * Everything a parser reports about a book, pushed as it is found rather than returned in one
 * lump. A 900-page PDF never sits in memory in full: the sink batches rows into the database as
 * they arrive.
 */
interface ImportSink {

  suspend fun metadata(
    title: String?,
    author: String? = null,
    language: String? = null,
    cover: ByteArray? = null,
  )

  /** Opens a new chapter. Everything pushed after this belongs to it until the next call. */
  suspend fun startChapter(title: String?)

  /** One paragraph of body text. Blank or whitespace-only text is dropped by the sink. */
  suspend fun paragraph(text: String, page: Int? = null)

  /**
   * Something was wrong with the source but parsing can continue — a chapter that would not
   * open, a page with no text layer. Collected and shown on the book rather than thrown away.
   */
  suspend fun warn(message: String)
}

/** Thrown when a file cannot be read as a book at all. */
class ImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface BookParser {

  val format: BookFormat

  /** Whether this parser handles the file, judged on MIME type first and extension second. */
  fun supports(mimeType: String?, fileName: String): Boolean

  suspend fun parse(context: Context, uri: Uri, sink: ImportSink)
}

/** Shared tidying every parser runs its text through before handing it to the sink. */
object TextNormaliser {

  private val COLLAPSE_WHITESPACE = Regex("[ \\t\\u00A0]+")
  private val SOFT_HYPHEN = '­'

  /**
   * Collapses runs of spaces, strips soft hyphens and trims. Line breaks inside a paragraph
   * become spaces, because a line break in the source is a typesetting artefact, not a pause.
   */
  fun paragraph(raw: String): String =
    raw
      .replace(SOFT_HYPHEN.toString(), "")
      .replace('\u000C', ' ')
      .lineSequence()
      .joinToString(" ") { it.trim() }
      .replace(COLLAPSE_WHITESPACE, " ")
      .trim()

  /**
   * Rejoins a word split across a line break by a hyphen, which is common in PDF extraction and
   * makes the speech engine say two half-words.
   */
  fun mendHyphenation(text: String): String =
    text.replace(Regex("(\\p{L})-\\s+(\\p{Ll})"), "$1$2")
}
