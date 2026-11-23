package com.akhouri.aloud.data

import java.sql.ResultSet
import kotlinx.coroutines.flow.Flow

private fun ResultSet.toBook() = BookEntity(
  id = getString("id"),
  path = getString("path"),
  title = getString("title"),
  author = getString("author"),
  format = runCatching { BookFormat.valueOf(getString("format")) }.getOrDefault(BookFormat.UNKNOWN),
  language = getString("language"),
  coverPath = getString("coverPath"),
  chapterCount = getInt("chapterCount"),
  paragraphCount = getInt("paragraphCount"),
  importedAt = getLong("importedAt"),
  lastOpenedAt = longOrNull("lastOpenedAt"),
  importState = runCatching { ImportState.valueOf(getString("importState")) }
    .getOrDefault(ImportState.FAILED),
  importError = getString("importError"),
  contentHash = getString("contentHash"),
  collectionId = longOrNull("collectionId"),
  collectionPosition = getInt("collectionPosition"),
  completed = getInt("completed") != 0,
)

private fun ResultSet.toChapter() = ChapterEntity(
  id = getLong("id"),
  bookId = getString("bookId"),
  chapterIndex = getInt("chapterIndex"),
  title = getString("title"),
  startParagraph = getInt("startParagraph"),
  paragraphCount = getInt("paragraphCount"),
)

private fun ResultSet.toParagraph() = ParagraphEntity(
  bookId = getString("bookId"),
  paragraphIndex = getInt("paragraphIndex"),
  chapterIndex = getInt("chapterIndex"),
  page = intOrNull("page"),
  text = getString("text"),
)

private fun ResultSet.toProgress() = ProgressEntity(
  bookId = getString("bookId"),
  paragraphIndex = getInt("paragraphIndex"),
  charOffset = getInt("charOffset"),
  updatedAt = getLong("updatedAt"),
)

private fun ResultSet.toSearchResult() = SearchResult(
  bookId = getString(1),
  bookTitle = getString(2),
  paragraphIndex = getInt(3),
  chapterIndex = getInt(4),
  chapterTitle = getString(5),
  page = getInt(6).takeUnless { wasNull() },
  snippet = getString(7),
)

class BookDao(private val db: Database) {

  fun observeAllWithProgress(): Flow<List<BookWithProgress>> = db.observe("books", "progress") {
    db.read {
      query(
        "SELECT b.*, p.paragraphIndex AS progressParagraph FROM books b " +
          "LEFT JOIN progress p ON p.bookId = b.id",
      ) { BookWithProgress(it.toBook(), it.intOrNull("progressParagraph")) }
    }
  }

  fun observe(bookId: String): Flow<BookEntity?> = db.observe("books") { find(bookId) }

  suspend fun find(bookId: String): BookEntity? =
    db.read { query("SELECT * FROM books WHERE id = ?", bookId) { it.toBook() }.firstOrNull() }

  suspend fun findByPath(path: String): BookEntity? = db.read {
    query("SELECT * FROM books WHERE path = ? COLLATE NOCASE LIMIT 1", path) { it.toBook() }.firstOrNull()
  }

  suspend fun findByHash(hash: String): BookEntity? = db.read {
    query("SELECT * FROM books WHERE contentHash = ? LIMIT 1", hash) { it.toBook() }.firstOrNull()
  }

  /** Inserts or updates in place. Never a replace, which would delete the book's contents. */
  suspend fun upsert(book: BookEntity) = db.write("books") {
    exec(
      """
      INSERT INTO books (id, path, title, author, format, language, coverPath, chapterCount,
        paragraphCount, importedAt, lastOpenedAt, importState, importError, contentHash,
        collectionId, collectionPosition, completed)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(id) DO UPDATE SET
        path = excluded.path, title = excluded.title, author = excluded.author,
        format = excluded.format, language = excluded.language, coverPath = excluded.coverPath,
        chapterCount = excluded.chapterCount, paragraphCount = excluded.paragraphCount,
        importedAt = excluded.importedAt, lastOpenedAt = excluded.lastOpenedAt,
        importState = excluded.importState, importError = excluded.importError,
        contentHash = excluded.contentHash, collectionId = excluded.collectionId,
        collectionPosition = excluded.collectionPosition, completed = excluded.completed
      """.trimIndent(),
      book.id, book.path, book.title, book.author, book.format, book.language, book.coverPath,
      book.chapterCount, book.paragraphCount, book.importedAt, book.lastOpenedAt,
      book.importState, book.importError, book.contentHash, book.collectionId,
      book.collectionPosition, book.completed,
    )
  }

  suspend fun updateImportState(bookId: String, state: ImportState, error: String? = null) =
    db.write("books") {
      exec("UPDATE books SET importState = ?, importError = ? WHERE id = ?", state, error, bookId)
    }

  /**
   * An import under way when the app last closed can never finish. Marking it failed is what
   * lets the same file be added again.
   */
  suspend fun failInterrupted(message: String) = db.write("books") {
    exec(
      "UPDATE books SET importState = 'FAILED', importError = ? " +
        "WHERE importState IN ('IMPORTING', 'PENDING')",
      message,
    )
  }

  suspend fun markOpened(bookId: String, at: Long) = db.write("books") {
    exec("UPDATE books SET lastOpenedAt = ? WHERE id = ?", at, bookId)
  }

  suspend fun delete(bookId: String) = db.write("books", "chapters", "paragraphs", "progress", "bookmarks") {
    exec("DELETE FROM books WHERE id = ?", bookId)
  }

  suspend fun setCompleted(bookId: String, completed: Boolean) = db.write("books") {
    exec("UPDATE books SET completed = ? WHERE id = ?", completed, bookId)
  }

  suspend fun withoutHash(): List<BookEntity> =
    db.read { query("SELECT * FROM books WHERE contentHash IS NULL") { it.toBook() } }

  suspend fun setHash(bookId: String, hash: String) = db.write("books") {
    exec("UPDATE books SET contentHash = ? WHERE id = ?", hash, bookId)
  }

  suspend fun editDetails(bookId: String, title: String, author: String?) = db.write("books") {
    exec("UPDATE books SET title = ?, author = ? WHERE id = ?", title, author, bookId)
  }

  suspend fun setCover(bookId: String, path: String?) = db.write("books") {
    exec("UPDATE books SET coverPath = ? WHERE id = ?", path, bookId)
  }

  suspend fun setCollection(bookId: String, collectionId: Long?, position: Int) = db.write("books") {
    exec(
      "UPDATE books SET collectionId = ?, collectionPosition = ? WHERE id = ?",
      collectionId, position, bookId,
    )
  }

  suspend fun inCollection(collectionId: Long): List<BookEntity> = db.read {
    query(
      "SELECT * FROM books WHERE collectionId = ? ORDER BY collectionPosition, title",
      collectionId,
    ) { it.toBook() }
  }

  suspend fun clearCollection(collectionId: Long) = db.write("books") {
    exec("UPDATE books SET collectionId = NULL, collectionPosition = 0 WHERE collectionId = ?", collectionId)
  }

  suspend fun searchByName(text: String): List<BookEntity> = db.read {
    query(
      "SELECT * FROM books WHERE title LIKE '%' || ? || '%' OR author LIKE '%' || ? || '%' " +
        "ORDER BY title COLLATE NOCASE LIMIT 50",
      text, text,
    ) { it.toBook() }
  }
}

class ChapterDao(private val db: Database) {

  fun observeForBook(bookId: String): Flow<List<ChapterEntity>> =
    db.observe("chapters") { forBook(bookId) }

  suspend fun forBook(bookId: String): List<ChapterEntity> = db.read {
    query("SELECT * FROM chapters WHERE bookId = ? ORDER BY chapterIndex", bookId) { it.toChapter() }
  }

  suspend fun insertAll(chapters: List<ChapterEntity>) = db.write("chapters") {
    prepareStatement(
      "INSERT OR REPLACE INTO chapters (bookId, chapterIndex, title, startParagraph, paragraphCount) " +
        "VALUES (?, ?, ?, ?, ?)",
    ).use { statement ->
      chapters.forEach { chapter ->
        statement.bindAll(
          arrayOf(chapter.bookId, chapter.chapterIndex, chapter.title, chapter.startParagraph, chapter.paragraphCount),
        )
        statement.addBatch()
      }
      statement.executeBatch()
    }
  }

  suspend fun deleteForBook(bookId: String) = db.write("chapters") {
    exec("DELETE FROM chapters WHERE bookId = ?", bookId)
  }
}

class ParagraphDao(private val db: Database) {

  suspend fun range(bookId: String, from: Int, limit: Int): List<ParagraphEntity> = db.read {
    query(
      "SELECT * FROM paragraphs WHERE bookId = ? AND paragraphIndex >= ? ORDER BY paragraphIndex LIMIT ?",
      bookId, from, limit,
    ) { it.toParagraph() }
  }

  suspend fun forChapter(bookId: String, chapterIndex: Int): List<ParagraphEntity> = db.read {
    query(
      "SELECT * FROM paragraphs WHERE bookId = ? AND chapterIndex = ? ORDER BY paragraphIndex",
      bookId, chapterIndex,
    ) { it.toParagraph() }
  }

  suspend fun insertAll(paragraphs: List<ParagraphEntity>) = db.write("paragraphs") {
    prepareStatement(
      "INSERT OR REPLACE INTO paragraphs (bookId, paragraphIndex, chapterIndex, page, text) " +
        "VALUES (?, ?, ?, ?, ?)",
    ).use { statement ->
      paragraphs.forEach { p ->
        statement.bindAll(arrayOf(p.bookId, p.paragraphIndex, p.chapterIndex, p.page, p.text))
        statement.addBatch()
      }
      statement.executeBatch()
    }
  }

  suspend fun deleteForBook(bookId: String) = db.write("paragraphs") {
    exec("DELETE FROM paragraphs WHERE bookId = ?", bookId)
  }

  /** Words and characters from a paragraph onwards, counting words by the gaps between them. */
  suspend fun sizeFrom(bookId: String, from: Int): TextSize = db.read {
    query(
      "SELECT SUM(LENGTH(text) - LENGTH(REPLACE(text, ' ', '')) + 1), SUM(LENGTH(text)) " +
        "FROM paragraphs WHERE bookId = ? AND paragraphIndex >= ?",
      bookId, from,
    ) { TextSize(it.getLong(1).takeUnless { _ -> it.wasNull() }, it.getLong(2).takeUnless { _ -> it.wasNull() }) }
      .firstOrNull() ?: TextSize(null, null)
  }

  /** [match] is an FTS expression; pass what the user typed through [FtsQuery.sanitise] first. */
  suspend fun searchInBook(bookId: String, match: String, limit: Int = 300): List<SearchResult> = db.read {
    query(SEARCH_SELECT + " AND p.bookId = ? ORDER BY p.paragraphIndex LIMIT ?", match, bookId, limit) {
      it.toSearchResult()
    }
  }

  suspend fun searchEverywhere(match: String, limit: Int = 100): List<SearchResult> = db.read {
    query(SEARCH_SELECT + " ORDER BY b.title, p.paragraphIndex LIMIT ?", match, limit) {
      it.toSearchResult()
    }
  }

  private companion object {
    const val SEARCH_SELECT =
      "SELECT p.bookId, b.title, p.paragraphIndex, p.chapterIndex, c.title, p.page, " +
        "snippet(paragraphs_fts, 0, '[', ']', '…', 24) " +
        "FROM paragraphs_fts " +
        "JOIN paragraphs p ON p.rowId = paragraphs_fts.rowid " +
        "JOIN books b ON b.id = p.bookId " +
        "LEFT JOIN chapters c ON c.bookId = p.bookId AND c.chapterIndex = p.chapterIndex " +
        "WHERE paragraphs_fts MATCH ?"
  }
}

class ProgressDao(private val db: Database) {

  suspend fun find(bookId: String): ProgressEntity? =
    db.read { query("SELECT * FROM progress WHERE bookId = ?", bookId) { it.toProgress() }.firstOrNull() }

  fun observe(bookId: String): Flow<ProgressEntity?> = db.observe("progress") { find(bookId) }

  suspend fun upsert(progress: ProgressEntity) = db.write("progress") {
    exec(
      "INSERT INTO progress (bookId, paragraphIndex, charOffset, updatedAt) VALUES (?, ?, ?, ?) " +
        "ON CONFLICT(bookId) DO UPDATE SET paragraphIndex = excluded.paragraphIndex, " +
        "charOffset = excluded.charOffset, updatedAt = excluded.updatedAt",
      progress.bookId, progress.paragraphIndex, progress.charOffset, progress.updatedAt,
    )
  }

  suspend fun delete(bookId: String) = db.write("progress") {
    exec("DELETE FROM progress WHERE bookId = ?", bookId)
  }
}

class BookmarkDao(private val db: Database) {

  fun observeForBook(bookId: String): Flow<List<BookmarkEntity>> = db.observe("bookmarks") {
    db.read {
      query("SELECT * FROM bookmarks WHERE bookId = ? ORDER BY paragraphIndex", bookId) {
        BookmarkEntity(
          id = it.getLong("id"),
          bookId = it.getString("bookId"),
          paragraphIndex = it.getInt("paragraphIndex"),
          charOffset = it.getInt("charOffset"),
          title = it.getString("title"),
          note = it.getString("note"),
          createdAt = it.getLong("createdAt"),
        )
      }
    }
  }

  suspend fun insert(bookmark: BookmarkEntity) = db.write("bookmarks") {
    exec(
      "INSERT INTO bookmarks (bookId, paragraphIndex, charOffset, title, note, createdAt) VALUES (?, ?, ?, ?, ?, ?)",
      bookmark.bookId, bookmark.paragraphIndex, bookmark.charOffset, bookmark.title, bookmark.note,
      bookmark.createdAt,
    )
  }

  suspend fun delete(id: Long) = db.write("bookmarks") { exec("DELETE FROM bookmarks WHERE id = ?", id) }
}

class CollectionDao(private val db: Database) {

  fun observeAll(): Flow<List<CollectionEntity>> = db.observe("collections") {
    db.read {
      query("SELECT * FROM collections ORDER BY name COLLATE NOCASE") {
        CollectionEntity(it.getLong("id"), it.getString("name"), it.getLong("createdAt"))
      }
    }
  }

  suspend fun insert(collection: CollectionEntity): Long = db.write("collections") {
    exec("INSERT INTO collections (name, createdAt) VALUES (?, ?)", collection.name, collection.createdAt)
    query("SELECT last_insert_rowid()") { it.getLong(1) }.first()
  }

  suspend fun rename(id: Long, name: String) = db.write("collections") {
    exec("UPDATE collections SET name = ? WHERE id = ?", name, id)
  }

  suspend fun delete(id: Long) = db.write("collections") { exec("DELETE FROM collections WHERE id = ?", id) }
}
