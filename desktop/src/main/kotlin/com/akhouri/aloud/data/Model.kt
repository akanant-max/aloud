package com.akhouri.aloud.data

enum class BookFormat { EPUB, PDF, TXT, MOBI, FB2, HTML, DOCX, UNKNOWN }

/**
 * How far an import has got. A book is only readable once it reaches [READY]; everything else is
 * shown in the library with its state, so a failed import is never silently an empty book.
 */
enum class ImportState { PENDING, IMPORTING, READY, FAILED }

data class BookEntity(
  val id: String,
  /** Where the original file is on this PC. */
  val path: String,
  val title: String,
  val author: String? = null,
  val format: BookFormat = BookFormat.UNKNOWN,
  val language: String? = null,
  val coverPath: String? = null,
  val chapterCount: Int = 0,
  val paragraphCount: Int = 0,
  val importedAt: Long = 0L,
  val lastOpenedAt: Long? = null,
  val importState: ImportState = ImportState.PENDING,
  val importError: String? = null,
  /** SHA-256 of the source file, so the same book is recognised wherever it is picked from. */
  val contentHash: String? = null,
  val collectionId: Long? = null,
  val collectionPosition: Int = 0,
  val completed: Boolean = false,
)

data class ChapterEntity(
  val id: Long = 0L,
  val bookId: String,
  val chapterIndex: Int,
  val title: String? = null,
  /** Index of this chapter's first paragraph in the book-wide paragraph sequence. */
  val startParagraph: Int,
  val paragraphCount: Int,
)

/**
 * One paragraph of normalised text. Every format collapses to this at import, so nothing
 * downstream needs to know whether a book began as EPUB, PDF or plain text.
 */
data class ParagraphEntity(
  val bookId: String,
  val paragraphIndex: Int,
  val chapterIndex: Int,
  val page: Int? = null,
  val text: String,
)

data class ProgressEntity(
  val bookId: String,
  val paragraphIndex: Int,
  val charOffset: Int = 0,
  val updatedAt: Long = 0L,
)

data class BookmarkEntity(
  val id: Long = 0L,
  val bookId: String,
  val paragraphIndex: Int,
  val charOffset: Int = 0,
  val title: String? = null,
  val note: String? = null,
  val createdAt: Long = 0L,
)

/** A named group of books that plays through like one anthology. Grouping costs no storage. */
data class CollectionEntity(
  val id: Long = 0L,
  val name: String,
  val createdAt: Long = 0L,
)

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

/** How much text there is; both are null when there is none. */
data class TextSize(val words: Long?, val characters: Long?)

/** A book with how far into it the listener has got, for the library. */
data class BookWithProgress(
  val book: BookEntity,
  val progressParagraph: Int?,
) {
  val fraction: Float
    get() = if (book.paragraphCount > 0) {
      ((progressParagraph ?: 0).toFloat() / book.paragraphCount).coerceIn(0f, 1f)
    } else {
      0f
    }
}
