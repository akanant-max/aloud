package com.akhouri.aloud.data

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** One remembered place in a book, and why it was remembered. */
data class HistoryEntry(
  val time: Long,
  val paragraphIndex: Int,
  val charOffset: Int,
  val chapterIndex: Int,
  val chapterTitle: String?,
  val reason: String,
  /** A few words from that spot, so it can be recognised. */
  val snippet: String,
  /** Recorded because the listener did something, which means they were awake for it. */
  val awake: Boolean,
)

/**
 * Narration carried on well past where the listener was last awake: offered on opening the
 * app, so a book fallen asleep to can be picked up where it was still being followed.
 */
data class PlaceSuggestion(
  val bookId: String,
  val entry: HistoryEntry,
  val nowChapterIndex: Int,
  val nowChapterTitle: String?,
)

/**
 * Where each book was when the listener pressed play, paused, jumped or opened the app. Kept in
 * a small file, newest first, a few dozen places a book.
 */
class ListeningHistory(private val file: File, private val scope: CoroutineScope) {

  private val lock = Any()
  private val books: MutableMap<String, MutableList<HistoryEntry>> by lazy { load() }

  private val _revision = MutableStateFlow(0)
  /** Bumped on every change, for screens showing the history. */
  val revision: StateFlow<Int> = _revision.asStateFlow()

  private val _suggestion = MutableStateFlow<PlaceSuggestion?>(null)
  val suggestion: StateFlow<PlaceSuggestion?> = _suggestion.asStateFlow()

  fun forBook(bookId: String): List<HistoryEntry> = synchronized(lock) { books[bookId]?.toList().orEmpty() }

  fun latestAwake(bookId: String): HistoryEntry? = synchronized(lock) { books[bookId]?.firstOrNull { it.awake } }

  fun record(bookId: String, entry: HistoryEntry) {
    synchronized(lock) {
      val list = books.getOrPut(bookId) { mutableListOf() }
      val last = list.firstOrNull()
      // The same spot moments later says nothing new; keep the earlier, clearer reason.
      if (last != null &&
        entry.time - last.time < SAME_MOMENT_MS &&
        kotlin.math.abs(entry.paragraphIndex - last.paragraphIndex) <= 2
      ) {
        if (entry.awake && !last.awake) list[0] = last.copy(awake = true)
        return@synchronized
      }
      list.add(0, entry)
      while (list.size > MAX_PER_BOOK) list.removeAt(list.lastIndex)
    }
    _revision.value++
    save()
  }

  fun forget(bookId: String) {
    synchronized(lock) { books.remove(bookId) }
    if (_suggestion.value?.bookId == bookId) _suggestion.value = null
    _revision.value++
    save()
  }

  fun suggest(suggestion: PlaceSuggestion?) {
    _suggestion.value = suggestion
  }

  fun dismissSuggestion() {
    _suggestion.value = null
  }

  private fun load(): MutableMap<String, MutableList<HistoryEntry>> {
    val result = mutableMapOf<String, MutableList<HistoryEntry>>()
    runCatching {
      if (!file.isFile) return result
      val root = JSONObject(file.readText())
      for (bookId in root.keys()) {
        val array = root.getJSONArray(bookId)
        val list = mutableListOf<HistoryEntry>()
        for (i in 0 until array.length()) {
          val o = array.getJSONObject(i)
          list += HistoryEntry(
            time = o.getLong("time"),
            paragraphIndex = o.getInt("paragraph"),
            charOffset = o.optInt("offset"),
            chapterIndex = o.optInt("chapter"),
            chapterTitle = o.optString("chapterTitle").takeIf { it.isNotEmpty() },
            reason = o.optString("reason"),
            snippet = o.optString("snippet"),
            awake = o.optBoolean("awake", true),
          )
        }
        result[bookId] = list
      }
    }
    return result
  }

  private val writing = Mutex()

  /** Writes the file soon. Each write takes the newest state, so the last one always wins. */
  private fun save() {
    scope.launch(Dispatchers.IO) { writing.withLock { write() } }
  }

  private fun write() {
    val json = synchronized(lock) {
      val root = JSONObject()
      for ((bookId, list) in books) {
        val array = JSONArray()
        for (e in list) {
          array.put(
            JSONObject()
              .put("time", e.time)
              .put("paragraph", e.paragraphIndex)
              .put("offset", e.charOffset)
              .put("chapter", e.chapterIndex)
              .put("chapterTitle", e.chapterTitle ?: "")
              .put("reason", e.reason)
              .put("snippet", e.snippet)
              .put("awake", e.awake),
          )
        }
        root.put(bookId, array)
      }
      root.toString()
    }
    run {
      runCatching {
        val temp = File(file.path + ".tmp")
        temp.writeText(json)
        if (!temp.renameTo(file)) {
          file.writeText(json)
          temp.delete()
        }
      }
    }
  }

  companion object {
    private const val MAX_PER_BOOK = 40
    private const val SAME_MOMENT_MS = 90_000L

    /** How far narration must have run on, unattended, before offering the way back. */
    const val DRIFT_PARAGRAPHS = 25
    const val DRIFT_MS = 10 * 60_000L

    const val PRESSED_PLAY = "Pressed play"
    const val PAUSED = "Paused"
    const val OPENED_APP = "Opened Aloud"
    const val BEFORE_JUMP = "Before jumping"
    const val SLEEP_TIMER = "Sleep timer stopped it"
  }
}
