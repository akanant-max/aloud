package com.akhouri.aloud.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.akhouri.aloud.data.entity.BookEntity
import com.akhouri.aloud.data.entity.BookmarkEntity
import com.akhouri.aloud.data.entity.ChapterEntity
import com.akhouri.aloud.data.entity.ImportState
import com.akhouri.aloud.data.entity.ParagraphEntity
import com.akhouri.aloud.data.entity.ProgressEntity
import com.akhouri.aloud.data.entity.SearchHistoryEntity
import kotlinx.coroutines.flow.Flow

/** A search hit, with enough context to show it in a list and jump straight to it. */
data class SearchResult(
  val bookId: String,
  val bookTitle: String,
  val paragraphIndex: Int,
  val chapterIndex: Int,
  val chapterTitle: String?,
  val page: Int?,
  val snippet: String,
)

@Dao
interface BookDao {

  @Query("SELECT * FROM books ORDER BY lastOpenedAt DESC, importedAt DESC")
  fun observeAll(): Flow<List<BookEntity>>

  @Query("SELECT * FROM books WHERE id = :bookId")
  fun observe(bookId: String): Flow<BookEntity?>

  @Query("SELECT * FROM books WHERE id = :bookId")
  suspend fun find(bookId: String): BookEntity?

  @Query("SELECT * FROM books WHERE uri = :uri LIMIT 1")
  suspend fun findByUri(uri: String): BookEntity?

  @Upsert
  suspend fun upsert(book: BookEntity)

  @Query("UPDATE books SET importState = :state, importError = :error WHERE id = :bookId")
  suspend fun updateImportState(bookId: String, state: ImportState, error: String? = null)

  @Query("UPDATE books SET lastOpenedAt = :at WHERE id = :bookId")
  suspend fun markOpened(bookId: String, at: Long)

  @Query("DELETE FROM books WHERE id = :bookId")
  suspend fun delete(bookId: String)
}

@Dao
interface ChapterDao {

  @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterIndex")
  fun observeForBook(bookId: String): Flow<List<ChapterEntity>>

  @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterIndex")
  suspend fun forBook(bookId: String): List<ChapterEntity>

  @Query("SELECT * FROM chapters WHERE bookId = :bookId AND chapterIndex = :chapterIndex")
  suspend fun find(bookId: String, chapterIndex: Int): ChapterEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(chapters: List<ChapterEntity>)

  @Query("DELETE FROM chapters WHERE bookId = :bookId")
  suspend fun deleteForBook(bookId: String)
}

@Dao
interface ParagraphDao {

  @Query(
    "SELECT * FROM paragraphs WHERE bookId = :bookId AND paragraphIndex >= :from " +
      "ORDER BY paragraphIndex LIMIT :limit",
  )
  suspend fun range(bookId: String, from: Int, limit: Int): List<ParagraphEntity>

  @Query("SELECT * FROM paragraphs WHERE bookId = :bookId AND paragraphIndex = :index")
  suspend fun at(bookId: String, index: Int): ParagraphEntity?

  @Query(
    "SELECT * FROM paragraphs WHERE bookId = :bookId AND chapterIndex = :chapterIndex " +
      "ORDER BY paragraphIndex",
  )
  suspend fun forChapter(bookId: String, chapterIndex: Int): List<ParagraphEntity>

  @Query("SELECT COUNT(*) FROM paragraphs WHERE bookId = :bookId")
  suspend fun count(bookId: String): Int

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(paragraphs: List<ParagraphEntity>)

  @Query("DELETE FROM paragraphs WHERE bookId = :bookId")
  suspend fun deleteForBook(bookId: String)

  /**
   * Full-text search within one book. [query] is an FTS MATCH expression, so callers should pass
   * it through [com.akhouri.aloud.data.FtsQuery.sanitise] first.
   */
  @Transaction
  @Query(
    """
    SELECT p.bookId AS bookId,
           b.title AS bookTitle,
           p.paragraphIndex AS paragraphIndex,
           p.chapterIndex AS chapterIndex,
           c.title AS chapterTitle,
           p.page AS page,
           snippet(paragraphs_fts, '[', ']', '…', -1, 24) AS snippet
    FROM paragraphs_fts
    JOIN paragraphs p ON p.rowId = paragraphs_fts.rowid
    JOIN books b ON b.id = p.bookId
    LEFT JOIN chapters c ON c.bookId = p.bookId AND c.chapterIndex = p.chapterIndex
    WHERE paragraphs_fts MATCH :query AND p.bookId = :bookId
    ORDER BY p.paragraphIndex
    LIMIT :limit
    """,
  )
  suspend fun searchInBook(bookId: String, query: String, limit: Int = 200): List<SearchResult>

  /** The same search across every imported book. */
  @Transaction
  @Query(
    """
    SELECT p.bookId AS bookId,
           b.title AS bookTitle,
           p.paragraphIndex AS paragraphIndex,
           p.chapterIndex AS chapterIndex,
           c.title AS chapterTitle,
           p.page AS page,
           snippet(paragraphs_fts, '[', ']', '…', -1, 24) AS snippet
    FROM paragraphs_fts
    JOIN paragraphs p ON p.rowId = paragraphs_fts.rowid
    JOIN books b ON b.id = p.bookId
    LEFT JOIN chapters c ON c.bookId = p.bookId AND c.chapterIndex = p.chapterIndex
    WHERE paragraphs_fts MATCH :query
    ORDER BY b.title, p.paragraphIndex
    LIMIT :limit
    """,
  )
  suspend fun searchEverywhere(query: String, limit: Int = 200): List<SearchResult>
}

@Dao
interface ProgressDao {

  @Query("SELECT * FROM progress WHERE bookId = :bookId")
  suspend fun find(bookId: String): ProgressEntity?

  @Query("SELECT * FROM progress WHERE bookId = :bookId")
  fun observe(bookId: String): Flow<ProgressEntity?>

  @Upsert
  suspend fun upsert(progress: ProgressEntity)
}

@Dao
interface BookmarkDao {

  @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY paragraphIndex")
  fun observeForBook(bookId: String): Flow<List<BookmarkEntity>>

  @Insert
  suspend fun insert(bookmark: BookmarkEntity): Long

  @Query("UPDATE bookmarks SET title = :title, note = :note WHERE id = :id")
  suspend fun edit(id: Long, title: String?, note: String?)

  @Query("DELETE FROM bookmarks WHERE id = :id")
  suspend fun delete(id: Long)
}

@Dao
interface SearchHistoryDao {

  @Query("SELECT * FROM search_history ORDER BY searchedAt DESC LIMIT :limit")
  fun observeRecent(limit: Int = 20): Flow<List<SearchHistoryEntity>>

  @Upsert
  suspend fun record(entry: SearchHistoryEntity)

  @Query("DELETE FROM search_history")
  suspend fun clear()
}
