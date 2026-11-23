package com.akhouri.aloud.data

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.sqlite.SQLiteConfig

/**
 * The library's SQLite database, with a full-text index over every paragraph.
 *
 * One connection, used one call at a time. Every write names the tables it touches, and anything
 * watching those tables through [observe] is re-queried, which is what keeps the screens live.
 */
class Database(file: File) {

  private val connection: Connection
  private val lock = Mutex()
  private val changes = MutableSharedFlow<Set<String>>(extraBufferCapacity = 64)

  init {
    Class.forName("org.sqlite.JDBC")
    val config = SQLiteConfig().apply {
      enforceForeignKeys(true)
      setJournalMode(SQLiteConfig.JournalMode.WAL)
      setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
      setBusyTimeout(10_000)
    }
    connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}", config.toProperties())
    createSchema()
  }

  suspend fun <T> read(block: Connection.() -> T): T =
    withContext(Dispatchers.IO) { lock.withLock { connection.block() } }

  /** Runs [block] in one transaction, then tells observers of [tables] that they changed. */
  suspend fun <T> write(vararg tables: String, block: Connection.() -> T): T {
    val result = withContext(Dispatchers.IO) {
      lock.withLock {
        connection.autoCommit = false
        try {
          connection.block().also { connection.commit() }
        } catch (e: Throwable) {
          runCatching { connection.rollback() }
          throw e
        } finally {
          connection.autoCommit = true
        }
      }
    }
    changes.tryEmit(tables.toSet())
    return result
  }

  /** Runs [query] now and again whenever any of [tables] is written to. */
  fun <T> observe(vararg tables: String, query: suspend () -> T): Flow<T> =
    changes
      .filter { changed -> tables.any { it in changed } }
      .map { }
      .onStart { emit(Unit) }
      .conflate()
      .map { query() }

  private fun createSchema() {
    val statements = listOf(
      """
      CREATE TABLE IF NOT EXISTS books (
        id TEXT PRIMARY KEY NOT NULL,
        path TEXT NOT NULL,
        title TEXT NOT NULL,
        author TEXT,
        format TEXT NOT NULL,
        language TEXT,
        coverPath TEXT,
        chapterCount INTEGER NOT NULL DEFAULT 0,
        paragraphCount INTEGER NOT NULL DEFAULT 0,
        importedAt INTEGER NOT NULL DEFAULT 0,
        lastOpenedAt INTEGER,
        importState TEXT NOT NULL,
        importError TEXT,
        contentHash TEXT,
        collectionId INTEGER,
        collectionPosition INTEGER NOT NULL DEFAULT 0,
        completed INTEGER NOT NULL DEFAULT 0
      )
      """,
      "CREATE INDEX IF NOT EXISTS books_hash ON books(contentHash)",
      """
      CREATE TABLE IF NOT EXISTS chapters (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        bookId TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,
        chapterIndex INTEGER NOT NULL,
        title TEXT,
        startParagraph INTEGER NOT NULL,
        paragraphCount INTEGER NOT NULL,
        UNIQUE (bookId, chapterIndex)
      )
      """,
      """
      CREATE TABLE IF NOT EXISTS paragraphs (
        rowId INTEGER PRIMARY KEY AUTOINCREMENT,
        bookId TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,
        paragraphIndex INTEGER NOT NULL,
        chapterIndex INTEGER NOT NULL,
        page INTEGER,
        text TEXT NOT NULL,
        UNIQUE (bookId, paragraphIndex)
      )
      """,
      // External-content full-text index, kept in step by the triggers below, so searching
      // never needs a book in memory.
      """
      CREATE VIRTUAL TABLE IF NOT EXISTS paragraphs_fts USING fts5(
        text, content='paragraphs', content_rowid='rowId',
        tokenize='unicode61 remove_diacritics 2'
      )
      """,
      """
      CREATE TRIGGER IF NOT EXISTS paragraphs_ai AFTER INSERT ON paragraphs BEGIN
        INSERT INTO paragraphs_fts(rowid, text) VALUES (new.rowId, new.text);
      END
      """,
      """
      CREATE TRIGGER IF NOT EXISTS paragraphs_ad AFTER DELETE ON paragraphs BEGIN
        INSERT INTO paragraphs_fts(paragraphs_fts, rowid, text) VALUES ('delete', old.rowId, old.text);
      END
      """,
      """
      CREATE TRIGGER IF NOT EXISTS paragraphs_au AFTER UPDATE ON paragraphs BEGIN
        INSERT INTO paragraphs_fts(paragraphs_fts, rowid, text) VALUES ('delete', old.rowId, old.text);
        INSERT INTO paragraphs_fts(rowid, text) VALUES (new.rowId, new.text);
      END
      """,
      """
      CREATE TABLE IF NOT EXISTS progress (
        bookId TEXT PRIMARY KEY NOT NULL REFERENCES books(id) ON DELETE CASCADE,
        paragraphIndex INTEGER NOT NULL,
        charOffset INTEGER NOT NULL DEFAULT 0,
        updatedAt INTEGER NOT NULL DEFAULT 0
      )
      """,
      """
      CREATE TABLE IF NOT EXISTS bookmarks (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        bookId TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,
        paragraphIndex INTEGER NOT NULL,
        charOffset INTEGER NOT NULL DEFAULT 0,
        title TEXT,
        note TEXT,
        createdAt INTEGER NOT NULL DEFAULT 0
      )
      """,
      """
      CREATE TABLE IF NOT EXISTS collections (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT NOT NULL,
        createdAt INTEGER NOT NULL DEFAULT 0
      )
      """,
    )
    connection.createStatement().use { statement ->
      statements.forEach { statement.executeUpdate(it.trimIndent()) }
    }
  }
}

// ---- small JDBC helpers ---------------------------------------------------------------------

internal fun Connection.exec(sql: String, vararg args: Any?): Int =
  prepareStatement(sql).use { statement ->
    statement.bindAll(args)
    statement.executeUpdate()
  }

internal fun <T> Connection.query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
  prepareStatement(sql).use { statement ->
    statement.bindAll(args)
    statement.executeQuery().use { rows ->
      val result = ArrayList<T>()
      while (rows.next()) result += map(rows)
      result
    }
  }

internal fun PreparedStatement.bindAll(args: Array<out Any?>) {
  args.forEachIndexed { index, value -> bind(index + 1, value) }
}

internal fun PreparedStatement.bind(position: Int, value: Any?) {
  when (value) {
    null -> setNull(position, Types.NULL)
    is String -> setString(position, value)
    is Int -> setInt(position, value)
    is Long -> setLong(position, value)
    is Boolean -> setInt(position, if (value) 1 else 0)
    is Float -> setDouble(position, value.toDouble())
    is Double -> setDouble(position, value)
    is Enum<*> -> setString(position, value.name)
    else -> setObject(position, value)
  }
}

internal fun ResultSet.longOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }

internal fun ResultSet.intOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }
