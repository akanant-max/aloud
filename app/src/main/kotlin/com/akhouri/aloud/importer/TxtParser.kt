package com.akhouri.aloud.importer

import android.content.Context
import android.net.Uri
import com.akhouri.aloud.data.entity.BookFormat
import java.io.BufferedInputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Plain text. Read as a stream and emitted paragraph by paragraph, so file size is bounded by
 * the longest paragraph rather than the document.
 */
class TxtParser : BookParser {

  override val format = BookFormat.TXT

  override fun supports(mimeType: String?, fileName: String): Boolean =
    mimeType == "text/plain" || fileName.endsWith(".txt", ignoreCase = true)

  override suspend fun parse(context: Context, uri: Uri, sink: ImportSink) {
    withContext(Dispatchers.IO) {
      val stream = context.contentResolver.openInputStream(uri)
        ?: throw ImportException("Could not open the file")

      BufferedInputStream(stream).use { buffered ->
        val charset = detectCharset(buffered)
        val title = displayName(context, uri)?.substringBeforeLast('.') ?: "Untitled"
        sink.metadata(title = title)

        var chapterOpen = false
        var paragraphsInChapter = 0
        val builder = StringBuilder()

        suspend fun flushParagraph() {
          val text = TextNormaliser.paragraph(builder.toString())
          builder.setLength(0)
          if (text.isEmpty()) return
          if (!chapterOpen) {
            sink.startChapter(null)
            chapterOpen = true
            paragraphsInChapter = 0
          }
          sink.paragraph(text)
          paragraphsInChapter++
        }

        InputStreamReader(buffered, charset).buffered().useLines { lines ->
          for (line in lines) {
            val trimmed = line.trim()

            if (trimmed.isEmpty()) {
              flushParagraph()
              continue
            }

            if (looksLikeHeading(trimmed)) {
              flushParagraph()
              sink.startChapter(trimmed)
              chapterOpen = true
              paragraphsInChapter = 0
              continue
            }

            // A long run with no headings gets split so the chapter list stays navigable.
            if (paragraphsInChapter >= SYNTHETIC_CHAPTER_SIZE) {
              flushParagraph()
              sink.startChapter(null)
              paragraphsInChapter = 0
            }

            if (builder.isNotEmpty()) builder.append(' ')
            builder.append(trimmed)
          }
          flushParagraph()
        }

        if (!chapterOpen) throw ImportException("The file has no readable text")
      }
    }
  }

  /** Reads the byte-order mark if there is one, else assumes UTF-8. */
  private fun detectCharset(stream: BufferedInputStream): Charset {
    stream.mark(4)
    val head = ByteArray(4)
    val read = stream.read(head, 0, 4)
    stream.reset()
    if (read < 2) return Charsets.UTF_8

    fun byteAt(index: Int) = head[index].toInt() and 0xFF

    return when {
      read >= 3 && byteAt(0) == 0xEF && byteAt(1) == 0xBB && byteAt(2) == 0xBF -> {
        stream.skip(3); Charsets.UTF_8
      }
      byteAt(0) == 0xFF && byteAt(1) == 0xFE -> {
        stream.skip(2); Charsets.UTF_16LE
      }
      byteAt(0) == 0xFE && byteAt(1) == 0xFF -> {
        stream.skip(2); Charsets.UTF_16BE
      }
      else -> Charsets.UTF_8
    }
  }

  /**
   * A short standalone line that reads like a chapter title. Deliberately conservative: a false
   * negative only costs a synthetic split, a false positive chops a paragraph in half.
   */
  private fun looksLikeHeading(line: String): Boolean {
    if (line.length > 72) return false
    if (line.endsWith('.') || line.endsWith(',') || line.endsWith(';')) return false
    if (CHAPTER_WORD.containsMatchIn(line)) return true
    val letters = line.filter { it.isLetter() }
    return letters.length >= 3 && letters.all { it.isUpperCase() }
  }

  private companion object {
    const val SYNTHETIC_CHAPTER_SIZE = 120
    val CHAPTER_WORD = Regex(
      "^(chapter|part|book|section|prologue|epilogue|appendix)\\b",
      RegexOption.IGNORE_CASE,
    )
  }
}
