package com.akhouri.aloud.importer

import com.akhouri.aloud.data.BookFormat
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * Everything a parser reports about a book, pushed as it is found rather than returned in one
 * lump, so a large book never sits in memory whole: the sink writes it out in batches.
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

  /** One paragraph of body text. Blank text is dropped by the sink. */
  suspend fun paragraph(text: String, page: Int? = null)

  /** Something was wrong with the source but parsing can continue. Shown on the book. */
  suspend fun warn(message: String)
}

/** Thrown when a file cannot be read as a book at all. */
class ImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface BookParser {

  val format: BookFormat

  fun supports(fileName: String): Boolean

  suspend fun parse(file: File, sink: ImportSink)
}

/**
 * Where heavy parsing runs. PDF structures nest deeply and the parser walks them recursively,
 * which overflows the stack an ordinary thread is given; this one has a far larger one. Being a
 * single thread also keeps imports strictly one at a time.
 */
object ImportThreads {
  val deepStack: CoroutineDispatcher = Executors.newSingleThreadExecutor { task ->
    Thread(null, task, "aloud-import", 256L * 1024 * 1024).apply { isDaemon = true }
  }.asCoroutineDispatcher()
}

/** Shared tidying every parser runs its text through before handing it to the sink. */
object TextNormaliser {

  private val COLLAPSE_WHITESPACE = Regex("[ \\t\\u00A0]+")

  /** Line breaks inside a paragraph become spaces: they are typesetting, not pauses. */
  fun paragraph(raw: String): String =
    raw
      .replace("­", "")
      .replace('\u000C', ' ')
      .lineSequence()
      .joinToString(" ") { it.trim() }
      .replace(COLLAPSE_WHITESPACE, " ")
      .trim()

  /** Rejoins a word split across a line break by a hyphen, common in PDF extraction. */
  fun mendHyphenation(text: String): String =
    text.replace(Regex("(\\p{L})-\\s+(\\p{Ll})"), "$1$2")
}
