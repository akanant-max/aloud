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
      file.forEachLine(Charsets.UTF_8) { line ->
        val f = line.split('\t')
        if (f.size < 9) return@forEachLine
        result.getOrPut(f[0]) { mutableListOf() } += HistoryEntry(
          time = f[1].toLong(),
          paragraphIndex = f[2].toInt(),
          charOffset = f[3].toInt(),
          chapterIndex = f[4].toInt(),
          chapterTitle = unescape(f[5]).takeIf { it.isNotEmpty() },
          reason = unescape(f[6]),
          snippet = unescape(f[7]),
          awake = f[8] == "1",
        )
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
    val text = synchronized(lock) {
      buildString {
        for ((bookId, list) in books) {
          for (e in list) {
            append(bookId).append('\t').append(e.time).append('\t').append(e.paragraphIndex).append('\t')
              .append(e.charOffset).append('\t').append(e.chapterIndex).append('\t')
              .append(escape(e.chapterTitle ?: "")).append('\t').append(escape(e.reason)).append('\t')
              .append(escape(e.snippet)).append('\t').append(if (e.awake) "1" else "0").append('\n')
          }
        }
      }
    }
    run {
      runCatching {
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (!temp.renameTo(file)) {
          file.writeText(text, Charsets.UTF_8)
          temp.delete()
        }
      }
    }
  }

  private fun escape(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "")

  private fun unescape(s: String): String {
    val out = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
      val c = s[i]
      if (c == '\\' && i + 1 < s.length) {
        when (s[i + 1]) {
          't' -> out.append('\t')
          'n' -> out.append('\n')
          else -> out.append(s[i + 1])
        }
        i += 2
      } else {
        out.append(c)
        i++
      }
    }
    return out.toString()
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
