package com.akhouri.aloud.importer

import com.akhouri.aloud.data.BookFormat
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.IOUtils
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper

/**
 * PDFs that carry a text layer, including ones already run through OCR elsewhere. A PDF with
 * no text layer is reported as such rather than imported as a book that plays silence.
 */
class PdfParser : BookParser {

  override val format = BookFormat.PDF

  override fun supports(fileName: String): Boolean = fileName.endsWith(".pdf", ignoreCase = true)

  override suspend fun parse(file: File, sink: ImportSink) {
    withContext(ImportThreads.deepStack) { readDocument(file, sink) }
  }

  private suspend fun readDocument(file: File, sink: ImportSink) {
    // Scratch space on disk rather than in memory, so memory use stays flat with page count.
    fun open(): PDDocument = try {
      Loader.loadPDF(file, IOUtils.createTempFileOnlyStreamCache())
    } catch (e: InvalidPasswordException) {
      throw ImportException("This PDF is password-protected and cannot be read.", e)
    } catch (e: Exception) {
      throw ImportException("This PDF could not be opened. It may be damaged.", e)
    }

    fun newStripper() = PDFTextStripper().apply {
      // Reads columns in the right order instead of straight across the page.
      sortByPosition = true
    }

    var document = open()
    try {
      val pageCount = document.numberOfPages
      if (pageCount == 0) throw ImportException("This PDF has no pages")

      val info = document.documentInformation
      sink.metadata(
        title = info?.title?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension,
        author = info?.author?.takeIf { it.isNotBlank() },
        cover = runCatching { renderFirstPage(document) }.getOrNull(),
      )

      val chapterStarts = runCatching { outlineByPage(document) }.getOrDefault(emptyMap())
      var stripper = newStripper()

      var openChapter = false
      var emptyPages = 0
      var paragraphsInChapter = 0
      var pagesSinceOpen = 0

      for (page in 1..pageCount) {
        coroutineContext.ensureActive()

        // The parser caches fonts and page resources and never lets them go, so a long book is
        // read in stretches with a fresh document for each.
        if (pagesSinceOpen >= REOPEN_EVERY_PAGES) {
          runCatching { document.close() }
          document = open()
          stripper = newStripper()
          pagesSinceOpen = 0
        }
        pagesSinceOpen++

        val raw = try {
          stripper.startPage = page
          stripper.endPage = page
          stripper.getText(document)
        } catch (e: Throwable) {
          sink.warn("Page $page could not be read")
          emptyPages++
          if (e is Error) {
            // Out of memory or stack on this page: start afresh from the next one.
            runCatching { document.close() }
            System.gc()
            document = open()
            stripper = newStripper()
            pagesSinceOpen = 0
          }
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
    } finally {
      runCatching { document.close() }
    }
  }

  /** A PDF has no cover of its own, so its first page stands in for one. */
  private fun renderFirstPage(document: PDDocument): ByteArray? {
    val image = PDFRenderer(document).renderImageWithDPI(0, COVER_DPI, ImageType.RGB)
    val out = ByteArrayOutputStream()
    ImageIO.write(image, "jpg", out)
    return out.toByteArray()
  }

  /** Chapter titles from the PDF outline, keyed by the page they start on. */
  private fun outlineByPage(doc: PDDocument): Map<Int, String> {
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
    return result
  }

  /**
   * Groups extracted lines into paragraphs. Blank lines are the break; a word hyphenated across
   * a line is rejoined, and an over-long run is split on sentence ends.
   */
  private fun splitParagraphs(pageText: String): List<String> {
    val paragraphs = ArrayList<String>()
    val builder = StringBuilder()

    fun flush() {
      if (builder.isBlank()) {
        builder.setLength(0)
        return
      }
      val text = TextNormaliser.paragraph(TextNormaliser.mendHyphenation(builder.toString()))
      builder.setLength(0)
      if (text.isEmpty()) return
      if (text.length <= MAX_PARAGRAPH_CHARS) paragraphs += text else paragraphs += splitLongRun(text)
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
    const val MAX_PARAGRAPH_CHARS = 1500
    const val SYNTHETIC_CHAPTER_SIZE = 200
    const val REOPEN_EVERY_PAGES = 120
    const val COVER_DPI = 60f
    val SENTENCE_END = Regex("(?<=[.!?])\\s+")
  }
}
