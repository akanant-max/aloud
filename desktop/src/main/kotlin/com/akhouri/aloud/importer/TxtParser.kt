package com.akhouri.aloud.importer

import com.akhouri.aloud.data.BookFormat
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Plain text, read as a stream, so file size is bounded by the longest paragraph. */
class TxtParser : BookParser {

  override val format = BookFormat.TXT

  override fun supports(fileName: String): Boolean = fileName.endsWith(".txt", ignoreCase = true)

  override suspend fun parse(file: File, sink: ImportSink) {
    withContext(Dispatchers.IO) {
      BufferedInputStream(file.inputStream()).use { buffered ->
        val charset = detectCharset(buffered)
        sink.metadata(title = file.nameWithoutExtension)

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
            // A long run with no headings is split so the chapter list stays navigable.
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

  /** A short standalone line that reads like a chapter title. Deliberately conservative. */
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
