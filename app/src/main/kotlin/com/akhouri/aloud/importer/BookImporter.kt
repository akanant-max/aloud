package com.akhouri.aloud.importer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.akhouri.aloud.data.AloudDatabase
import com.akhouri.aloud.data.entity.BookEntity
import com.akhouri.aloud.data.entity.ChapterEntity
import com.akhouri.aloud.data.entity.ImportState
import com.akhouri.aloud.data.entity.ParagraphEntity
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns a file the user picked into rows in the database.
 *
 * Every format lands in the same shape — a flat run of paragraphs grouped into chapters — so
 * after import nothing downstream has to know or care where a book came from.
 */
class BookImporter(
  private val context: Context,
  private val database: AloudDatabase,
) {

  private val parsers: List<BookParser> = listOf(EpubParser(), PdfParser(), TxtParser())

  /** Whether any parser recognises this file, used to filter a folder scan. */
  fun isSupported(mimeType: String?, fileName: String): Boolean =
    parsers.any { it.supports(mimeType, fileName) }

  /**
   * Imports one file. Returns the book id. The book row exists from the start with its state
   * set, so a long import is visible in the library while it runs and a failure is visible
   * afterwards rather than vanishing.
   */
  suspend fun import(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
    val fileName = displayName(context, uri) ?: "Untitled"
    val mimeType = context.contentResolver.getType(uri)

    val parser = parsers.firstOrNull { it.supports(mimeType, fileName) }
      ?: return@withContext Result.failure(
        ImportException("Aloud does not read this kind of file: $fileName"),
      )

    persistPermission(uri)

    // The same file can be reached through different pickers and folders, each handing over a
    // different address for it, so identity is decided by what is in the file.
    val hash = runCatching { hashOf(uri) }.getOrNull()
    backfillHashes()

    val existing = database.bookDao().findByUri(uri.toString())
      ?: hash?.let { database.bookDao().findByHash(it) }

    // Only a book whose earlier import failed may be imported again, as a retry.
    if (existing != null && existing.importState != ImportState.FAILED) {
      return@withContext Result.failure(
        ImportException("\u201C${existing.title}\u201D is already in your library"),
      )
    }

    val bookId = existing?.id ?: UUID.randomUUID().toString()
    if (existing != null) clearContent(bookId)

    val fallbackTitle = TitleCleaner.fromFileName(fileName)
    val base = existing ?: BookEntity(
      id = bookId,
      uri = uri.toString(),
      title = fallbackTitle,
      importedAt = System.currentTimeMillis(),
    )
    database.bookDao().upsert(
      base.copy(
        uri = uri.toString(),
        format = parser.format,
        contentHash = hash,
        importState = ImportState.IMPORTING,
        importError = null,
      ),
    )

    val sink = DatabaseSink(bookId, fallbackTitle)
    return@withContext try {
      parser.parse(context, uri, sink)
      sink.finish()
      Result.success(bookId)
    } catch (e: CancellationException) {
      database.bookDao().delete(bookId)
      throw e
    } catch (e: OutOfMemoryError) {
      // Not an Exception, so it would otherwise pass every handler and end the process.
      Log.w(TAG, "Out of memory importing $fileName", e)
      val message = "This file is too large for this phone's memory to import."
      runCatching { clearContent(bookId) }
      database.bookDao().updateImportState(bookId, ImportState.FAILED, message)
      Result.failure(ImportException(message))
    } catch (e: Exception) {
      Log.w(TAG, "Import failed for $fileName", e)
      val message = (e as? ImportException)?.message
        ?: "This file could not be read (${e.javaClass.simpleName})"
      database.bookDao().updateImportState(bookId, ImportState.FAILED, message)
      Result.failure(ImportException(message, e))
    }
  }

  private fun hashOf(uri: Uri): String? {
    val digest = MessageDigest.getInstance("SHA-256")
    val stream = context.contentResolver.openInputStream(uri) ?: return null
    stream.use { input ->
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  /**
   * Books imported before duplicate detection existed have no hash yet. Filling them in here
   * means they are recognised too; a book whose file has since gone is simply skipped.
   */
  private suspend fun backfillHashes() {
    database.bookDao().withoutHash().forEach { book ->
      val hash = runCatching { hashOf(Uri.parse(book.uri)) }.getOrNull()
      if (hash != null) database.bookDao().setHash(book.id, hash)
    }
  }

  /** Keeps read access to the file across reboots, so a book does not go missing later. */
  private fun persistPermission(uri: Uri) {
    runCatching {
      context.contentResolver.takePersistableUriPermission(
        uri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION,
      )
    }.onFailure { Log.d(TAG, "No persistable permission for $uri", it) }
  }

  private suspend fun clearContent(bookId: String) {
    database.paragraphDao().deleteForBook(bookId)
    database.chapterDao().deleteForBook(bookId)
  }

  /**
   * Collects what a parser pushes and writes it out in batches, so peak memory stays flat
   * whatever the size of the book.
   */
  private inner class DatabaseSink(
    private val bookId: String,
    private val fallbackTitle: String,
  ) : ImportSink {

    private val paragraphBuffer = ArrayList<ParagraphEntity>(BATCH_SIZE)
    private val chapters = ArrayList<ChapterEntity>()
    private val warnings = ArrayList<String>()

    private var title: String? = null
    private var author: String? = null
    private var language: String? = null
    private var coverPath: String? = null

    private var paragraphIndex = 0
    private var chapterIndex = -1
    private var chapterStart = 0

    override suspend fun metadata(
      title: String?,
      author: String?,
      language: String?,
      cover: ByteArray?,
    ) {
      this.title = title?.let(TitleCleaner::clean)?.takeIf { it.isNotEmpty() }
      this.author = author?.trim()?.takeIf { it.isNotEmpty() }
      this.language = language?.trim()?.takeIf { it.isNotEmpty() }
      if (cover != null && cover.isNotEmpty()) {
        coverPath = runCatching { writeCover(cover) }.getOrNull()
      }
    }

    override suspend fun startChapter(title: String?) {
      closeChapter()
      chapterIndex++
      chapterStart = paragraphIndex
      chapters += ChapterEntity(
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

      paragraphBuffer += ParagraphEntity(
        bookId = bookId,
        paragraphIndex = paragraphIndex,
        chapterIndex = chapterIndex,
        page = page,
        text = text,
      )
      paragraphIndex++

      if (paragraphBuffer.size >= BATCH_SIZE) flushParagraphs()
    }

    override suspend fun warn(message: String) {
      if (warnings.size < MAX_WARNINGS) warnings += message
    }

    suspend fun finish() {
      closeChapter()
      flushParagraphs()

      if (paragraphIndex == 0) throw ImportException("No readable text was found")

      database.chapterDao().insertAll(chapters)

      val row = database.bookDao().find(bookId)
        ?: throw ImportException("The book was removed while it was being imported")
      // Copied from the stored row so that anything set elsewhere survives a retry.
      database.bookDao().upsert(
        row.copy(
          title = title ?: fallbackTitle,
          author = author,
          language = language,
          coverPath = coverPath,
          chapterCount = chapters.size,
          paragraphCount = paragraphIndex,
          importState = ImportState.READY,
          // Warnings are advisory, not a failure: the book is readable, some of it was skipped.
          importError = warnings.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        ),
      )
    }

    private fun closeChapter() {
      if (chapterIndex < 0) return
      val index = chapters.lastIndex
      if (index >= 0) {
        chapters[index] = chapters[index].copy(paragraphCount = paragraphIndex - chapterStart)
      }
    }

    private suspend fun flushParagraphs() {
      if (paragraphBuffer.isEmpty()) return
      database.paragraphDao().insertAll(paragraphBuffer.toList())
      paragraphBuffer.clear()
    }

    private fun writeCover(bytes: ByteArray): String {
      val dir = File(context.filesDir, "covers").apply { mkdirs() }
      val file = File(dir, "$bookId.img")
      file.writeBytes(bytes)
      return file.absolutePath
    }
  }

  private companion object {
    const val TAG = "BookImporter"
    const val BATCH_SIZE = 400
    const val MAX_WARNINGS = 10
  }
}
