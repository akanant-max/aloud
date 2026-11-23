package com.akhouri.aloud.importer

import com.akhouri.aloud.AppPaths
import com.akhouri.aloud.data.BookDao
import com.akhouri.aloud.data.BookEntity
import com.akhouri.aloud.data.ChapterDao
import com.akhouri.aloud.data.ChapterEntity
import com.akhouri.aloud.data.ImportState
import com.akhouri.aloud.data.ParagraphDao
import com.akhouri.aloud.data.ParagraphEntity
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns a book file into rows in the database. Every format lands in the same shape — a run
 * of paragraphs grouped into chapters — so nothing downstream cares where a book came from.
 */
class BookImporter(
  private val books: BookDao,
  private val chapters: ChapterDao,
  private val paragraphs: ParagraphDao,
) {

  private val parsers: List<BookParser> = listOf(
    EpubParser(),
    PdfParser(),
    MobiParser(),
    Fb2Parser(),
    DocxParser(),
    HtmlParser(),
    TxtParser(),
  )

  fun isSupported(fileName: String): Boolean = parsers.any { it.supports(fileName) }

  /** File-name patterns for the open dialog. */
  val patterns: List<String> =
    listOf("epub", "pdf", "txt", "mobi", "azw", "azw3", "prc", "fb2", "docx", "html", "htm", "xhtml")

  /**
   * Imports one file and returns the book's id. The book row exists from the start, so a long
   * import is visible in the library while it runs and a failure stays visible afterwards.
   */
  suspend fun import(file: File): Result<String> = withContext(Dispatchers.IO) {
    val parser = parsers.firstOrNull { it.supports(file.name) }
      ?: return@withContext Result.failure(ImportException("Aloud does not read this kind of file: ${file.name}"))
    if (!file.isFile) {
      return@withContext Result.failure(ImportException("The file could not be found: ${file.name}"))
    }

    // Identity is decided by contents, so the same book is recognised wherever it is picked from.
    val hash = runCatching { hashOf(file) }.getOrNull()
    val existing = books.findByPath(file.absolutePath) ?: hash?.let { books.findByHash(it) }

    // Only a book whose earlier import failed may be imported again, as a retry.
    if (existing != null && existing.importState != ImportState.FAILED) {
      return@withContext Result.failure(ImportException("“${existing.title}” is already in your library"))
    }

    val bookId = existing?.id ?: UUID.randomUUID().toString()
    if (existing != null) clearContent(bookId)

    val fallbackTitle = TitleCleaner.fromFileName(file.name)
    val base = existing ?: BookEntity(
      id = bookId,
      path = file.absolutePath,
      title = fallbackTitle,
      importedAt = System.currentTimeMillis(),
    )
    books.upsert(
      base.copy(
        path = file.absolutePath,
        format = parser.format,
        contentHash = hash,
        importState = ImportState.IMPORTING,
        importError = null,
      ),
    )

    val sink = DatabaseSink(bookId, fallbackTitle)
    try {
      parser.parse(file, sink)
      sink.finish()
      Result.success(bookId)
    } catch (e: CancellationException) {
      books.delete(bookId)
      throw e
    } catch (e: Throwable) {
      // Throwable, not Exception: running out of memory or stack is an Error, and would
      // otherwise pass every handler and end the app along with the rest of a batch.
      val message = when (e) {
        is ImportException -> e.message ?: "This file could not be read"
        is OutOfMemoryError -> "This file is too large to import."
        is StackOverflowError -> "This file is built too intricately to be read."
        else -> {
          val where = e.stackTrace.firstOrNull()?.let { " at ${it.fileName}:${it.lineNumber}" }
          "This file could not be read (${e.javaClass.simpleName}${where.orEmpty()})"
        }
      }
      runCatching { clearContent(bookId) }
      runCatching { books.updateImportState(bookId, ImportState.FAILED, message) }
      Result.failure(ImportException(message, e))
    }
  }

  private fun hashOf(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(256 * 1024)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  private suspend fun clearContent(bookId: String) {
    paragraphs.deleteForBook(bookId)
    chapters.deleteForBook(bookId)
  }

  /** Collects what a parser pushes and writes it in batches, keeping memory flat. */
  private inner class DatabaseSink(
    private val bookId: String,
    private val fallbackTitle: String,
  ) : ImportSink {

    private val buffer = ArrayList<ParagraphEntity>(BATCH_SIZE)
    private val chapterList = ArrayList<ChapterEntity>()
    private val warnings = ArrayList<String>()

    private var title: String? = null
    private var author: String? = null
    private var language: String? = null
    private var coverPath: String? = null

    private var paragraphIndex = 0
    private var chapterIndex = -1
    private var chapterStart = 0

    override suspend fun metadata(title: String?, author: String?, language: String?, cover: ByteArray?) {
      this.title = title?.let(TitleCleaner::clean)?.takeIf { it.isNotEmpty() }
      this.author = author?.trim()?.takeIf { it.isNotEmpty() }
      this.language = language?.trim()?.takeIf { it.isNotEmpty() }
      if (cover != null && cover.isNotEmpty()) {
        coverPath = runCatching { writeCover(bookId, cover) }.getOrNull()
      }
    }

    override suspend fun startChapter(title: String?) {
      closeChapter()
      chapterIndex++
      chapterStart = paragraphIndex
      chapterList += ChapterEntity(
        bookId = bookId,
        chapterIndex = chapterIndex,
        title = title?.trim()?.takeIf { it.isNotEmpty() },
        startParagraph = chapterStart,
        paragraphCount = 0,
      )
    }

    override suspend fun paragraph(text: String, page: Int?) {
      if (text.isBlank()) return
      if (chapterIndex < 0) startChapter(null)
      buffer += ParagraphEntity(bookId, paragraphIndex, chapterIndex, page, text)
      paragraphIndex++
      if (buffer.size >= BATCH_SIZE) flush()
    }

    override suspend fun warn(message: String) {
      if (warnings.size < MAX_WARNINGS) warnings += message
    }

    suspend fun finish() {
      closeChapter()
      flush()
      if (paragraphIndex == 0) throw ImportException("No readable text was found")

      chapters.insertAll(chapterList)
      val row = books.find(bookId) ?: throw ImportException("The book was removed while it was being imported")
      books.upsert(
        row.copy(
          title = title ?: fallbackTitle,
          author = author,
          language = language,
          coverPath = coverPath,
          chapterCount = chapterList.size,
          paragraphCount = paragraphIndex,
          importState = ImportState.READY,
          // Warnings are advisory: the book is readable, some of it was skipped.
          importError = warnings.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        ),
      )
    }

    private fun closeChapter() {
      if (chapterIndex < 0) return
      val last = chapterList.lastIndex
      if (last >= 0) chapterList[last] = chapterList[last].copy(paragraphCount = paragraphIndex - chapterStart)
    }

    private suspend fun flush() {
      if (buffer.isEmpty()) return
      paragraphs.insertAll(buffer.toList())
      buffer.clear()
    }
  }

  companion object {
    private const val BATCH_SIZE = 500
    private const val MAX_WARNINGS = 10

    /** Saves cover image bytes under a name whose extension matches what they are. */
    fun writeCover(bookId: String, bytes: ByteArray): String {
      val png = bytes.size > 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()
      val gif = bytes.size > 3 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte()
      val extension = when {
        png -> "png"
        gif -> "gif"
        else -> "jpg"
      }
      // A fresh name each time, so nothing keeps showing a cached older cover.
      val file = File(AppPaths.covers, "$bookId-${System.currentTimeMillis()}.$extension")
      file.writeBytes(bytes)
      return file.absolutePath
    }
  }
}
