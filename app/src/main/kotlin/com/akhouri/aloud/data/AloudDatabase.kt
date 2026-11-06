package com.akhouri.aloud.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.akhouri.aloud.data.entity.BookEntity
import com.akhouri.aloud.data.entity.BookFormat
import com.akhouri.aloud.data.entity.BookmarkEntity
import com.akhouri.aloud.data.entity.ChapterEntity
import com.akhouri.aloud.data.entity.ImportState
import com.akhouri.aloud.data.entity.ParagraphEntity
import com.akhouri.aloud.data.entity.ParagraphFts
import com.akhouri.aloud.data.entity.ProgressEntity
import com.akhouri.aloud.data.entity.SearchHistoryEntity

object Converters {

  @TypeConverter
  fun formatToString(value: BookFormat): String = value.name

  @TypeConverter
  fun stringToFormat(value: String): BookFormat =
    runCatching { BookFormat.valueOf(value) }.getOrDefault(BookFormat.UNKNOWN)

  @TypeConverter
  fun importStateToString(value: ImportState): String = value.name

  @TypeConverter
  fun stringToImportState(value: String): ImportState =
    runCatching { ImportState.valueOf(value) }.getOrDefault(ImportState.FAILED)
}

@Database(
  entities = [
    BookEntity::class,
    ChapterEntity::class,
    ParagraphEntity::class,
    ParagraphFts::class,
    ProgressEntity::class,
    BookmarkEntity::class,
    SearchHistoryEntity::class,
  ],
  version = 1,
  exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AloudDatabase : RoomDatabase() {

  abstract fun bookDao(): BookDao

  abstract fun chapterDao(): ChapterDao

  abstract fun paragraphDao(): ParagraphDao

  abstract fun progressDao(): ProgressDao

  abstract fun bookmarkDao(): BookmarkDao

  abstract fun searchHistoryDao(): SearchHistoryDao

  companion object {

    fun build(context: Context): AloudDatabase =
      Room.databaseBuilder(context.applicationContext, AloudDatabase::class.java, "aloud.db")
        // Foreign keys drive the cascade that clears a book's chapters, paragraphs, FTS rows,
        // progress and bookmarks when the book goes.
        .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
        .build()
  }
}
