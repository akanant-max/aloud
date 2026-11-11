package com.akhouri.aloud.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.akhouri.aloud.data.entity.BookFormat
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * PDFs that carry a text layer, including ones that have already been through OCR elsewhere.
 *
 * A PDF with no text layer is reported as such rather than imported as an empty book, since an
 * empty book that plays silence is the most confusing possible outcome.
 */
class PdfParser : BookParser {

  override val format = BookFormat.PDF

  override fun supports(mimeType: String?, fileName: String): Boolean =
    mimeType == "application/pdf" || fileName.endsWith(".pdf", ignoreCase = true)

  override suspend fun parse(context: Context, uri: Uri, sink: ImportSink) {
    withContext(Dispatchers.IO) {
      // Copied to disk first, then opened from there. Handing PdfBox the stream makes it read
      // the entire file into memory before parsing a page, which a large textbook does not
      // survive.
      val cached = copyToCache(context, uri, "pdf")
      try {
        readDocument(context, uri, cached, sink)
      } finally {
        cached.delete()
      }
    }
  }

  private suspend fun readDocument(context: Context, uri: Uri, file: File, sink: ImportSink) {
    run {
      val document = try {
        // Scratch space on disk rather than in the heap, so memory use stays flat however
        // many pages there are.
        PDDocument.load(
          file,
          MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir),
        )
      } catch (e: Exception) {
        throw ImportException(
          "This PDF could not be opened. It may be damaged or password-protected.",
          e,
        )
      }

      document.use { doc ->
        if (doc.isEncrypted) {
          throw ImportException("This PDF is password-protected and cannot be read.")
        }

        val pageCount = doc.numberOfPages
        if (pageCount == 0) throw ImportException("This PDF has no pages")

        val info = doc.documentInformation
        sink.metadata(
          title = info?.title?.takeIf { it.isNotBlank() }
            ?: displayName(context, uri)?.substringBeforeLast('.'),
          author = info?.author?.takeIf { it.isNotBlank() },
          cover = runCatching { renderFirstPage(file) }.getOrNull(),
        )

        val chapterStarts = outlineByPage(doc)
        val stripper = PDFTextStripper().apply {
          // Reads columns in the right order instead of straight across the page.
          sortByPosition = true
        }

        var openChapter = false
        var emptyPages = 0
        var paragraphsInChapter = 0

        for (page in 1..pageCount) {
          coroutineContext.ensureActive()

          val raw = try {
            stripper.startPage = page
            stripper.endPage = page
            stripper.getText(doc)
          } catch (e: Exception) {
            sink.warn("Page $page could not be read")
            emptyPages++
            continue
          }

          if (raw.count { !it.isWhitespace() } < MIN_CHARS_PER_PAGE) {
            emptyPages++
            continue
          }

          chapterStarts[page]?.let { title ->
            sink.startChapter(title)
            openChapter = true
            paragraphsInChapter = 0
          }

          val paragraphs = splitParagraphs(raw)
          if (paragraphs.isEmpty()) {
            emptyPages++
            continue
          }

          // Either nothing has opened a chapter yet, or an outline-less run has grown long
          // enough that the chapter list needs another entry to stay navigable.
          if (!openChapter || paragraphsInChapter >= SYNTHETIC_CHAPTER_SIZE) {
            sink.startChapter(null)
            openChapter = true
            paragraphsInChapter = 0
          }

          paragraphs.forEach {
            sink.paragraph(it, page = page)
            paragraphsInChapter++
          }
        }

        if (!openChapter) {
          throw ImportException(
            if (emptyPages == pageCount) {
              "This PDF has no text layer — the pages are images. It would need OCR before it " +
                "can be read aloud."
            } else {
              "No readable text was found in this PDF"
            },
          )
        }

        if (emptyPages > pageCount / 2) {
          sink.warn("$emptyPages of $pageCount pages had no text layer and were skipped.")
        }
      }
    }
  }

  /** A PDF carries no cover of its own, so its first page stands in for one. */
  private fun renderFirstPage(file: File): ByteArray? {
    val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    try {
      val renderer = PdfRenderer(descriptor)
      try {
        if (renderer.pageCount == 0) return null
        val page = renderer.openPage(0)
        try {
          val width = COVER_WIDTH_PX
          val height = (width.toFloat() * page.height / page.width.coerceAtLeast(1))
            .toInt()
            .coerceIn(1, COVER_WIDTH_PX * 3)
          val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
          bitmap.eraseColor(Color.WHITE)
          page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
          val out = ByteArrayOutputStream()
          bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
          bitmap.recycle()
          return out.toByteArray()
        } finally {
          page.close()
        }
      } finally {
        renderer.close()
      }
    } finally {
      runCatching { descriptor.close() }
    }
  }

  /** Chapter titles from the PDF outline, keyed by the page they start on. */
  private fun outlineByPage(doc: PDDocument): Map<Int, String> = runCatching {
    val outline = doc.documentCatalog?.documentOutline ?: return emptyMap()
    val result = LinkedHashMap<Int, String>()

    fun walk(item: PDOutlineItem?, depth: Int) {
      var next = item
      while (next != null) {
        val node = next
        runCatching {
          val page = node.findDestinationPage(doc)
          val title = node.title?.trim()
          if (page != null && !title.isNullOrEmpty()) {
            val number = doc.pages.indexOf(page) + 1
            if (number > 0) result.putIfAbsent(number, title)
          }
        }
        // Only the top two levels, or a textbook's outline floods the chapter list.
        if (depth < 1) walk(node.firstChild, depth + 1)
        next = node.nextSibling
      }
    }

    walk(outline.firstChild, 0)
    result
  }.getOrDefault(emptyMap())

  /**
   * Groups extracted lines into paragraphs. Blank lines are the break; a word hyphenated across
   * a line break is rejoined, and an over-long run is split on sentence ends so no single
   * paragraph becomes unmanageable.
   */
  private fun splitParagraphs(pageText: String): List<String> {
    val paragraphs = ArrayList<String>()
    val builder = StringBuilder()

    fun flush() {
      if (builder.isBlank()) {
        builder.setLength(0)
        return
      }
      val mended = TextNormaliser.mendHyphenation(builder.toString())
      builder.setLength(0)
      val text = TextNormaliser.paragraph(mended)
      if (text.isEmpty()) return
      if (text.length <= MAX_PARAGRAPH_CHARS) {
        paragraphs += text
      } else {
        paragraphs += splitLongRun(text)
      }
    }

    for (line in pageText.lineSequence()) {
      if (line.isBlank()) {
        flush()
      } else {
        if (builder.isNotEmpty()) builder.append('\n')
        builder.append(line)
      }
    }
    flush()
    return paragraphs
  }

  private fun splitLongRun(text: String): List<String> {
    val pieces = ArrayList<String>()
    val builder = StringBuilder()
    for (sentence in SENTENCE_END.split(text)) {
      if (builder.length + sentence.length > MAX_PARAGRAPH_CHARS && builder.isNotEmpty()) {
        pieces += builder.toString().trim()
        builder.setLength(0)
      }
      if (builder.isNotEmpty()) builder.append(' ')
      builder.append(sentence.trim())
    }
    if (builder.isNotBlank()) pieces += builder.toString().trim()
    return pieces.filter { it.isNotEmpty() }
  }

  private companion object {
    const val MIN_CHARS_PER_PAGE = 20
    const val COVER_WIDTH_PX = 480
    const val MAX_PARAGRAPH_CHARS = 1500
    const val SYNTHETIC_CHAPTER_SIZE = 200
    val SENTENCE_END = Regex("(?<=[.!?])\\s+")
  }
}
