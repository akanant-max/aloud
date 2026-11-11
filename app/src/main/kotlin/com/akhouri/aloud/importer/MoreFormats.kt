package com.akhouri.aloud.importer

import android.content.Context
import android.net.Uri
import android.text.Html
import com.akhouri.aloud.data.entity.BookFormat
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/*
 * The formats below are read for their words only. Layout, images and styling are discarded,
 * which is all narration needs and is what keeps each reader small.
 */

private const val SYNTHETIC_CHAPTER_SIZE = 200
private const val MAX_TEXT_FILE_BYTES = 80L * 1024 * 1024

/** Emits one chapter, breaking an over-long one up so the chapter list stays navigable. */
internal suspend fun emitChapter(sink: ImportSink, title: String?, paragraphs: List<String>) {
  if (paragraphs.isEmpty()) return
  paragraphs.chunked(SYNTHETIC_CHAPTER_SIZE).forEachIndexed { index, part ->
    sink.startChapter(if (index == 0) title else null)
    part.forEach { sink.paragraph(it) }
  }
}

private fun stripTags(markup: String): String {
  @Suppress("DEPRECATION")
  val text = Html.fromHtml(markup, Html.FROM_HTML_MODE_COMPACT).toString()
  return TextNormaliser.paragraph(text)
}

/** Reads a text-based file, honouring the encoding it declares for itself. */
private fun readDeclaredText(context: Context, uri: Uri): String {
  val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (true) {
      val read = input.read(buffer)
      if (read < 0) break
      out.write(buffer, 0, read)
      if (out.size() > MAX_TEXT_FILE_BYTES) throw ImportException("This file is too large to import.")
    }
    out.toByteArray()
  } ?: throw ImportException("Could not open the file")

  val head = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1)
  val declared = Regex("(?:encoding|charset)\\s*=\\s*[\"']?([\\w-]+)", RegexOption.IGNORE_CASE)
    .find(head)?.groupValues?.get(1)
  val charset = declared?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
  return String(bytes, charset)
}

// ---- MOBI / AZW / AZW3 / PRC ----------------------------------------------------------------

/**
 * Kindle and Mobipocket books without DRM. The container is a Palm database: a table of
 * records, the first holding the headers and the next few the compressed text, which is HTML.
 */
class MobiParser : BookParser {

  override val format = BookFormat.MOBI

  override fun supports(mimeType: String?, fileName: String): Boolean {
    val name = fileName.lowercase()
    return EXTENSIONS.any { name.endsWith(it) } || mimeType == "application/x-mobipocket-ebook"
  }

  override suspend fun parse(context: Context, uri: Uri, sink: ImportSink) {
    withContext(Dispatchers.IO) {
      val cached = copyToCache(context, uri, "mobi")
      try {
        val fallbackTitle = displayName(context, uri)?.substringBeforeLast('.')
        RandomAccessFile(cached, "r").use { file -> read(file, fallbackTitle, sink) }
      } catch (e: ImportException) {
        throw e
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        throw ImportException("This file could not be read as a Kindle or Mobipocket book.", e)
      } finally {
        cached.delete()
      }
    }
  }

  private suspend fun read(file: RandomAccessFile, fallbackTitle: String?, sink: ImportSink) {
    val length = file.length()
    if (length < 78) throw ImportException("This is not a valid Kindle or Mobipocket book.")

    file.seek(76)
    val recordCount = file.readUnsignedShort()
    if (recordCount < 2) throw ImportException("This book has no text in it.")

    val offsets = LongArray(recordCount + 1)
    for (i in 0 until recordCount) {
      file.seek(78L + i * 8L)
      offsets[i] = file.readInt().toLong() and 0xFFFFFFFFL
    }
    offsets[recordCount] = length

    fun record(index: Int): ByteArray {
      val start = offsets[index]
      val size = offsets[index + 1] - start
      if (start < 0 || size < 0 || start + size > length || size > Int.MAX_VALUE) {
        throw ImportException("This book's file is damaged.")
      }
      val bytes = ByteArray(size.toInt())
      file.seek(start)
      file.readFully(bytes)
      return bytes
    }

    val header = record(0)
    if (header.size < 16) throw ImportException("This book's file is damaged.")
    val fields = ByteBuffer.wrap(header)

    val compression = fields.getShort(0).toInt() and 0xFFFF
    val textRecords = fields.getShort(8).toInt() and 0xFFFF
    val encryption = fields.getShort(12).toInt() and 0xFFFF

    if (encryption != 0) {
      throw ImportException("This book is DRM-protected and cannot be read.")
    }
    if (compression != NO_COMPRESSION && compression != PALMDOC_COMPRESSION) {
      throw ImportException(
        "This book uses an older Mobipocket compression that Aloud does not read. " +
          "Converting it to EPUB first will work.",
      )
    }

    var charset: Charset = runCatching { Charset.forName("windows-1252") }
      .getOrDefault(Charsets.ISO_8859_1)
    var extraFlags = 0
    var title: String? = null
    var author: String? = null

    val hasMobiHeader = header.size >= 32 &&
      String(header, 16, 4, Charsets.ISO_8859_1) == "MOBI"
    if (hasMobiHeader) {
      val headerLength = fields.getInt(20)
      if (fields.getInt(28) == 65001) charset = Charsets.UTF_8

      if (header.size >= 92) {
        val nameOffset = fields.getInt(84)
        val nameLength = fields.getInt(88)
        if (nameOffset > 0 && nameLength in 1..1024 && nameOffset + nameLength <= header.size) {
          title = String(header, nameOffset, nameLength, charset)
        }
      }
      if (headerLength >= 0xE4 && header.size >= 0xF4) {
        extraFlags = fields.getShort(0xF2).toInt() and 0xFFFF
      }

      val hasExth = header.size >= 0x84 && (fields.getInt(0x80) and 0x40) != 0
      val exth = 16 + headerLength
      if (hasExth && exth > 0 && exth + 12 <= header.size &&
        String(header, exth, 4, Charsets.ISO_8859_1) == "EXTH"
      ) {
        val entries = fields.getInt(exth + 8)
        var position = exth + 12
        for (i in 0 until entries.coerceIn(0, 500)) {
          if (position + 8 > header.size) break
          val type = fields.getInt(position)
          val size = fields.getInt(position + 4)
          if (size < 8 || position + size > header.size) break
          val value = String(header, position + 8, size - 8, charset)
          when (type) {
            EXTH_AUTHOR -> if (author == null) author = value
            EXTH_TITLE -> title = value
          }
          position += size
        }
      }
    }

    sink.metadata(title = title?.takeIf { it.isNotBlank() } ?: fallbackTitle, author = author)

    val text = ByteArrayOutputStream()
    for (index in 1..textRecords) {
      if (index >= recordCount) break
      coroutineContext.ensureActive()
      val data = record(index)
      val size = (data.size - trailingBytes(data, extraFlags)).coerceIn(0, data.size)
      if (compression == PALMDOC_COMPRESSION) {
        decompress(data, size, text)
      } else {
        text.write(data, 0, size)
      }
    }

    var markup = String(text.toByteArray(), charset)
    // Newer Kindle files append stylesheets after the last page; narrating those helps no one.
    val lastPage = markup.lastIndexOf("</html>", ignoreCase = true)
    if (lastPage > markup.length / 2) markup = markup.substring(0, lastPage + 7)

    var emitted = false
    for (piece in PAGE_BREAK.split(markup)) {
      coroutineContext.ensureActive()
      val paragraphs = HtmlText.toParagraphs(piece)
      if (paragraphs.isEmpty()) continue
      emitChapter(sink, HtmlText.firstHeading(piece), paragraphs)
      emitted = true
    }
    if (!emitted) throw ImportException("No readable text was found in this book.")
  }

  /** Each text record can end with bookkeeping bytes that are not part of the text. */
  private fun trailingBytes(data: ByteArray, flags: Int): Int {
    var end = data.size
    for (bit in 15 downTo 1) {
      if ((flags and (1 shl bit)) == 0) continue
      var value = 0
      for (i in maxOf(0, end - 4) until end) {
        val byte = data[i].toInt() and 0xFF
        if ((byte and 0x80) != 0) value = 0
        value = (value shl 7) or (byte and 0x7F)
      }
      end = (end - value).coerceAtLeast(0)
    }
    if ((flags and 1) != 0 && end > 0) {
      end -= ((data[end - 1].toInt() and 0x3) + 1)
    }
    return data.size - end.coerceAtLeast(0)
  }

  /** PalmDOC compression: literals, short runs, space-plus-letter pairs and back-references. */
  private fun decompress(data: ByteArray, size: Int, into: ByteArrayOutputStream) {
    var out = ByteArray(8192)
    var length = 0
    fun put(byte: Int) {
      if (length == out.size) out = out.copyOf(out.size * 2)
      out[length++] = byte.toByte()
    }

    var i = 0
    while (i < size) {
      val c = data[i++].toInt() and 0xFF
      when {
        c == 0 || c in 0x09..0x7F -> put(c)
        c in 0x01..0x08 -> {
          var n = c
          while (n-- > 0 && i < size) put(data[i++].toInt() and 0xFF)
        }
        c >= 0xC0 -> {
          put(' '.code)
          put(c xor 0x80)
        }
        else -> {
          if (i >= size) break
          val pair = (c shl 8) or (data[i++].toInt() and 0xFF)
          val distance = (pair shr 3) and 0x7FF
          val count = (pair and 0x7) + 3
          if (distance == 0 || distance > length) continue
          repeat(count) { put(out[length - distance].toInt() and 0xFF) }
        }
      }
    }
    into.write(out, 0, length)
  }

  private companion object {
    val EXTENSIONS = listOf(".mobi", ".azw", ".azw3", ".prc")
    const val NO_COMPRESSION = 1
    const val PALMDOC_COMPRESSION = 2
    const val EXTH_AUTHOR = 100
    const val EXTH_TITLE = 503
    val PAGE_BREAK = Regex("<mbp:pagebreak[^>]*>|</html\\s*>", RegexOption.IGNORE_CASE)
  }
}

// ---- FB2 ------------------------------------------------------------------------------------

/** FictionBook: one XML file, sections for chapters, pictures embedded as base64. */
class Fb2Parser : BookParser {

  override val format = BookFormat.FB2

  override fun supports(mimeType: String?, fileName: String): Boolean =
    fileName.endsWith(".fb2", ignoreCase = true)

  override suspend fun parse(context: Context, uri: Uri, sink: ImportSink) {
    withContext(Dispatchers.IO) {
      val xml = readDeclaredText(context, uri).replace(BINARY, " ")

      val title = BOOK_TITLE.find(xml)?.groupValues?.get(1)?.let(::stripTags)
      val author = AUTHOR.find(xml)?.groupValues?.get(1)?.let(::stripTags)
      sink.metadata(
        title = title?.takeIf { it.isNotEmpty() }
          ?: displayName(context, uri)?.substringBeforeLast('.'),
        author = author?.takeIf { it.isNotEmpty() },
      )

      val body = xml.substringAfter("<body", missingDelimiterValue = xml)
      var emitted = false
      for (section in SECTION.split(body)) {
        coroutineContext.ensureActive()
        val heading = SECTION_TITLE.find(section)?.groupValues?.get(1)?.let(::stripTags)
        val paragraphs = HtmlText.toParagraphs(section)
        if (paragraphs.isEmpty()) continue
        emitChapter(sink, heading?.takeIf { it.isNotEmpty() && it.length <= 120 }, paragraphs)
        emitted = true
      }
      if (!emitted) throw ImportException("No readable text was found in this book.")
    }
  }

  private companion object {
    val OPTIONS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    val BINARY = Regex("<binary\\b.*?</binary\\s*>", OPTIONS)
    val BOOK_TITLE = Regex("<book-title[^>]*>(.*?)</book-title\\s*>", OPTIONS)
    val AUTHOR = Regex("<author[^>]*>(.*?)</author\\s*>", OPTIONS)
    val SECTION = Regex("<section\\b[^>]*>", RegexOption.IGNORE_CASE)
    val SECTION_TITLE = Regex("<title[^>]*>(.*?)</title\\s*>", OPTIONS)
  }
}

// ---- HTML -----------------------------------------------------------------------------------

/** A saved web page or single-file HTML book, split into chapters at its main headings. */
class HtmlParser : BookParser {

  override val format = BookFormat.HTML

  override fun supports(mimeType: String?, fileName: String): Boolean {
    val name = fileName.lowercase()
    return name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".xhtml")
  }

  override suspend fun parse(context: Context, uri: Uri, sink: ImportSink) {
    withContext(Dispatchers.IO) {
      val html = readDeclaredText(context, uri)
      val title = TITLE.find(html)?.groupValues?.get(1)?.let(::stripTags)
      sink.metadata(
        title = title?.takeIf { it.isNotEmpty() }
          ?: displayName(context, uri)?.substringBeforeLast('.'),
      )

      var emitted = false
      for (piece in HEADING_START.split(html)) {
        coroutineContext.ensureActive()
        val paragraphs = HtmlText.toParagraphs(piece)
        if (paragraphs.isEmpty()) continue
        emitChapter(sink, HtmlText.firstHeading(piece), paragraphs)
        emitted = true
      }
      if (!emitted) throw ImportException("No readable text was found in this page.")
    }
  }

  private companion object {
    val TITLE = Regex(
      "<title[^>]*>(.*?)</title\\s*>",
      setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** Splits just before each top-level heading, keeping the heading with what follows it. */
    val HEADING_START = Regex("(?=<h[12]\\b)", RegexOption.IGNORE_CASE)
  }
}

// ---- DOCX -----------------------------------------------------------------------------------

/** Word documents: a ZIP whose main part lists paragraphs, with headings marked by style. */
class DocxParser : BookParser {

  override val format = BookFormat.DOCX

  override fun supports(mimeType: String?, fileName: String): Boolean =
    fileName.endsWith(".docx", ignoreCase = true)

  override suspend fun parse(context: Context, uri: Uri, sink: ImportSink) {
    withContext(Dispatchers.IO) {
      val cached = copyToCache(context, uri, "docx")
      try {
        ZipFile(cached).use { zip ->
          val entry = zip.getEntry("word/document.xml")
            ?: throw ImportException("This is not a valid Word document.")
          val xml = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }

          val core = zip.getEntry("docProps/core.xml")
            ?.let { zip.getInputStream(it).use { s -> s.readBytes().toString(Charsets.UTF_8) } }
          sink.metadata(
            title = core?.let { CORE_TITLE.find(it)?.groupValues?.get(1) }?.let(::stripTags)
              ?.takeIf { it.isNotEmpty() }
              ?: displayName(context, uri)?.substringBeforeLast('.'),
            author = core?.let { CORE_AUTHOR.find(it)?.groupValues?.get(1) }?.let(::stripTags)
              ?.takeIf { it.isNotEmpty() },
          )

          var chapterTitle: String? = null
          val pending = ArrayList<String>()
          var emitted = false

          for (match in PARAGRAPH.findAll(xml)) {
            coroutineContext.ensureActive()
            val block = match.value
            val text = TextNormaliser.paragraph(
              RUN_TEXT.findAll(block.replace(TAB, " ")).joinToString("") { run ->
                stripEntities(run.groupValues[1])
              },
            )
            if (text.isEmpty()) continue

            if (HEADING_STYLE.containsMatchIn(block) && text.length <= 120) {
              if (pending.isNotEmpty()) {
                emitChapter(sink, chapterTitle, pending.toList())
                emitted = true
                pending.clear()
              }
              chapterTitle = text
            } else {
              pending += text
            }
          }
          if (pending.isNotEmpty()) {
            emitChapter(sink, chapterTitle, pending.toList())
            emitted = true
          }
          if (!emitted) throw ImportException("No readable text was found in this document.")
        }
      } catch (e: ImportException) {
        throw e
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        throw ImportException("This Word document could not be opened.", e)
      } finally {
        cached.delete()
      }
    }
  }

  private fun stripEntities(text: String): String =
    text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
      .replace("&apos;", "'").replace("&amp;", "&")

  private companion object {
    val OPTIONS = setOf(RegexOption.DOT_MATCHES_ALL)
    val PARAGRAPH = Regex("<w:p[ >].*?</w:p>", OPTIONS)
    val RUN_TEXT = Regex("<w:t(?:\\s[^>]*)?>(.*?)</w:t>", OPTIONS)
    val TAB = Regex("<w:tab\\s*/>|<w:br\\s*/>")
    val HEADING_STYLE = Regex("<w:pStyle\\s+w:val=\"(Heading|Title)", RegexOption.IGNORE_CASE)
    val CORE_TITLE = Regex("<dc:title[^>]*>(.*?)</dc:title>", OPTIONS)
    val CORE_AUTHOR = Regex("<dc:creator[^>]*>(.*?)</dc:creator>", OPTIONS)
  }
}
