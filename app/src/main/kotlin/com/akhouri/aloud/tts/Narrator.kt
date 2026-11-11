package com.akhouri.aloud.tts

import android.util.Log
import com.akhouri.aloud.data.AloudDatabase
import com.akhouri.aloud.data.entity.ChapterEntity
import com.akhouri.aloud.data.entity.ProgressEntity
import com.akhouri.aloud.playback.PlaybackKeepAlive
import com.akhouri.aloud.util.CrashReporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Everything the UI and the media session need to know about narration, in one place. */
data class NarrationState(
  val bookId: String? = null,
  val bookTitle: String = "",
  val author: String? = null,
  val chapters: List<ChapterEntity> = emptyList(),
  val chapterIndex: Int = 0,
  val chapterTitle: String? = null,
  val paragraphIndex: Int = 0,
  val charOffset: Int = 0,
  val paragraphCount: Int = 0,
  val isPlaying: Boolean = false,
  val isPreparing: Boolean = false,
  /** Set when narration stopped for a reason the user needs to see. */
  val error: String? = null,
  val speechRate: Float = 1.0f,
  val pitch: Float = 1.0f,
) {
  val hasBook: Boolean get() = bookId != null
}

/**
 * Drives narration: pulls paragraphs from the database, chunks them, keeps the engine fed, and
 * tracks exactly where in the book the voice has got to.
 *
 * Two rules shape the design. Every utterance carries a generation stamp, so callbacks that
 * arrive after a seek or a stop are recognised as stale and ignored rather than advancing the
 * position — that is what stops a seek from producing duplicated or overlapping speech. And the
 * stored position is always the start of the oldest utterance not yet finished, so whenever
 * playback stops, for any reason, resuming repeats at most one chunk and never skips one.
 */
class Narrator(
  private val engine: TtsEngine,
  private val database: AloudDatabase,
  private val keepAlive: PlaybackKeepAlive,
  private val scope: CoroutineScope,
) {

  private val _state = MutableStateFlow(NarrationState())
  val state: StateFlow<NarrationState> = _state.asStateFlow()

  private val mutex = Mutex()

  /** Chunks loaded from the database but not yet handed to the engine. */
  private val pending = ArrayDeque<SpeechChunk>()

  /** Handed to the engine and awaiting completion, oldest first. */
  private val inFlight = LinkedHashMap<String, SpeechChunk>()

  /** Bumped on every seek, stop and book change; stale callbacks carry an older value. */
  private var generation = 0
  private var sequence = 0

  private var consecutiveFailures = 0
  private var endOfBook = false

  init {
    scope.launch {
      engine.events.collect(::onEngineEvent)
    }

    // A call coming in, another app taking over the speakers, or headphones being pulled out
    // all pause narration rather than talking over whatever took priority.
    keepAlive.onInterrupted = { scope.launch { pause() } }
    keepAlive.onRegained = { scope.launch { play() } }
  }

  // ---- public control ---------------------------------------------------------------------

  /** Loads a book and positions narration, without starting it. */
  suspend fun open(bookId: String, paragraphIndex: Int? = null, charOffset: Int = 0) {
    mutex.withLock {
      val book = database.bookDao().find(bookId) ?: return
      val chapters = database.chapterDao().forBook(bookId)
      val saved = database.progressDao().find(bookId)

      val startParagraph = paragraphIndex ?: saved?.paragraphIndex ?: 0
      val startOffset = if (paragraphIndex != null) charOffset else saved?.charOffset ?: 0

      invalidate()
      endOfBook = false
      paragraphLengths.clear()
      _state.value = _state.value.copy(
        bookId = bookId,
        bookTitle = book.title,
        author = book.author,
        chapters = chapters,
        chapterIndex = chapterOf(chapters, startParagraph),
        chapterTitle = chapters.getOrNull(chapterOf(chapters, startParagraph))?.title,
        paragraphIndex = startParagraph,
        charOffset = startOffset,
        paragraphCount = book.paragraphCount,
        isPlaying = false,
        error = null,
      )
      database.bookDao().markOpened(bookId, System.currentTimeMillis())
    }
  }

  suspend fun play() {
    mutex.withLock {
      val current = _state.value
      if (current.bookId == null || current.isPlaying) return

      _state.value = current.copy(isPreparing = true, error = null)

      val ready = engine.ensureReady(preferredEngine = TtsEngine.GOOGLE_TTS)
      if (ready.isFailure) {
        _state.value = _state.value.copy(
          isPreparing = false,
          isPlaying = false,
          error = ready.exceptionOrNull()?.message
            ?: "No speech engine is available. Install Google Text-to-Speech, then try again.",
        )
        return
      }

      engine.setSpeechRate(current.speechRate)
      engine.setPitch(current.pitch)

      // Tells the system this app is playing media, and keeps the CPU awake so the next
      // utterance can be queued with the screen off.
      keepAlive.acquire()

      consecutiveFailures = 0
      endOfBook = false
      _state.value = _state.value.copy(isPreparing = false, isPlaying = true)
      refill(flushFirst = true)
    }
  }

  suspend fun pause() {
    mutex.withLock {
      if (!_state.value.isPlaying) return
      // Position already points at the oldest unfinished chunk, so nothing is recomputed here.
      invalidate()
      keepAlive.release()
      _state.value = _state.value.copy(isPlaying = false)
      persistProgress()
    }
  }

  suspend fun togglePlayPause() {
    if (_state.value.isPlaying) pause() else play()
  }

  suspend fun stop() {
    mutex.withLock {
      invalidate()
      keepAlive.release()
      _state.value = _state.value.copy(isPlaying = false)
      persistProgress()
    }
  }

  /** Jumps to an exact position — what a search result or a bookmark does. */
  suspend fun seekTo(paragraphIndex: Int, charOffset: Int = 0) {
    mutex.withLock {
      val current = _state.value
      val bookId = current.bookId ?: return
      val target = paragraphIndex.coerceIn(0, (current.paragraphCount - 1).coerceAtLeast(0))

      val wasPlaying = current.isPlaying
      invalidate()
      endOfBook = false

      _state.value = current.copy(
        paragraphIndex = target,
        charOffset = charOffset,
        chapterIndex = chapterOf(current.chapters, target),
        chapterTitle = current.chapters.getOrNull(chapterOf(current.chapters, target))?.title,
        isPlaying = wasPlaying,
        error = null,
      )
      persistProgress()
      if (wasPlaying) refill(flushFirst = true)
      Unit
    }
  }

  suspend fun seekToChapter(chapterIndex: Int) {
    val chapters = _state.value.chapters
    val chapter = chapters.getOrNull(chapterIndex.coerceIn(0, chapters.lastIndex.coerceAtLeast(0)))
      ?: return
    seekTo(chapter.startParagraph)
  }

  suspend fun nextChapter() {
    seekToChapter(_state.value.chapterIndex + 1)
  }

  /**
   * Back a chapter, or to the start of this one if narration is already some way in — the
   * behaviour people expect from a previous-track button.
   */
  suspend fun previousChapter() {
    val current = _state.value
    val chapter = current.chapters.getOrNull(current.chapterIndex)
    val intoChapter = chapter?.let { current.paragraphIndex - it.startParagraph } ?: 0
    if (intoChapter > RESTART_CHAPTER_THRESHOLD) {
      seekToChapter(current.chapterIndex)
    } else {
      seekToChapter(current.chapterIndex - 1)
    }
  }

  /** Skips by whole paragraphs, which is what rewind and fast-forward map onto for speech. */
  suspend fun skipParagraphs(delta: Int) {
    seekTo(_state.value.paragraphIndex + delta)
  }

  suspend fun setSpeechRate(rate: Float) {
    mutex.withLock {
      val clamped = rate.coerceIn(TtsEngine.MIN_RATE, TtsEngine.MAX_RATE)
      _state.value = _state.value.copy(speechRate = clamped)
      engine.setSpeechRate(clamped)
      // The engine applies a rate change to new utterances only, so what is already queued is
      // re-queued from the current position at the new speed.
      if (_state.value.isPlaying) refill(flushFirst = true)
    }
  }

  suspend fun setPitch(pitch: Float) {
    mutex.withLock {
      val clamped = pitch.coerceIn(TtsEngine.MIN_PITCH, TtsEngine.MAX_PITCH)
      _state.value = _state.value.copy(pitch = clamped)
      engine.setPitch(clamped)
      if (_state.value.isPlaying) refill(flushFirst = true)
    }
  }

  /** Re-applies the engine or voice without losing the reading position. */
  suspend fun useVoice(enginePackage: String?, voiceName: String?) {
    mutex.withLock {
      val wasPlaying = _state.value.isPlaying
      invalidate()
      engine.ensureReady(enginePackage)
      engine.setSpeechRate(_state.value.speechRate)
      engine.setPitch(_state.value.pitch)
      voiceName?.let { engine.setVoice(it) }
      _state.value = _state.value.copy(isPlaying = wasPlaying)
      if (wasPlaying) refill(flushFirst = true)
    }
  }

  // ---- engine callbacks -------------------------------------------------------------------

  private fun onEngineEvent(event: TtsEvent) {
    val stamp = event.utteranceId.substringBefore(':').toIntOrNull()
    if (stamp != generation) return // stale: belongs to a cancelled run

    scope.launch {
      try {
        mutex.withLock {
          if (stamp != generation) return@withLock
          when (event) {
            is TtsEvent.Started -> onStarted(event)
            is TtsEvent.Done -> onDone(event)
            is TtsEvent.Failed -> onFailed(event)
            is TtsEvent.Range -> Unit // word-level highlighting is not wired to the UI yet
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // Narration stops and says so, rather than the exception reaching the default
        // handler and killing the app mid-chapter.
        CrashReporter.record(e, "narration event ${event.utteranceId}")
        invalidate()
        keepAlive.release()
        _state.value = _state.value.copy(
          isPlaying = false,
          error = "Narration stopped unexpectedly (${e.javaClass.simpleName}). " +
            "Reopen the book to carry on — your place was saved.",
        )
      }
    }
  }

  private fun onStarted(event: TtsEvent.Started) {
    val chunk = inFlight[event.utteranceId] ?: return
    consecutiveFailures = 0
    _state.value = _state.value.copy(
      paragraphIndex = chunk.paragraphIndex,
      charOffset = chunk.startOffset,
      chapterIndex = chapterOf(_state.value.chapters, chunk.paragraphIndex),
      chapterTitle = _state.value.chapters
        .getOrNull(chapterOf(_state.value.chapters, chunk.paragraphIndex))?.title,
    )
  }

  private suspend fun onDone(event: TtsEvent.Done) {
    val finished = inFlight.remove(event.utteranceId) ?: return
    consecutiveFailures = 0

    // Position moves to the start of whatever is still outstanding, so stopping now and
    // resuming later repeats at most the chunk currently being spoken.
    val next = inFlight.values.firstOrNull() ?: pending.firstOrNull()
    if (next != null) {
      _state.value = _state.value.copy(
        paragraphIndex = next.paragraphIndex,
        charOffset = next.startOffset,
      )
    } else {
      _state.value = _state.value.copy(
        paragraphIndex = finished.paragraphIndex,
        charOffset = finished.endOffset,
      )
    }

    persistProgress()

    if (inFlight.isEmpty() && pending.isEmpty() && endOfBook) {
      keepAlive.release()
      _state.value = _state.value.copy(isPlaying = false)
      return
    }
    if (_state.value.isPlaying) refill(flushFirst = false)
  }

  private suspend fun onFailed(event: TtsEvent.Failed) {
    val failed = inFlight.remove(event.utteranceId)
    consecutiveFailures++
    Log.w(TAG, "Utterance failed (${event.errorCode}), skipping: ${failed?.text?.take(40)}")

    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
      invalidate()
      keepAlive.release()
      _state.value = _state.value.copy(
        isPlaying = false,
        error = "The speech engine kept failing. Check its voice data is installed, then try again.",
      )
      return
    }
    // A single bad chunk should not end the session, so move past it and carry on.
    if (_state.value.isPlaying) refill(flushFirst = false)
  }

  // ---- queue ------------------------------------------------------------------------------

  /** Cancels everything in flight and makes any callback still to arrive stale. */
  private fun invalidate() {
    generation++
    pending.clear()
    inFlight.clear()
    engine.stop()
  }

  /**
   * Tops the engine up to [QUEUE_DEPTH] utterances. Keeping more than one queued is what stops
   * an audible gap at every chunk boundary; keeping only a few is what keeps pause responsive.
   */
  private suspend fun refill(flushFirst: Boolean) {
    val current = _state.value
    val bookId = current.bookId ?: return

    if (flushFirst) {
      generation++
      pending.clear()
      inFlight.clear()
      engine.stop()
    }

    while (inFlight.size < QUEUE_DEPTH) {
      if (pending.isEmpty()) {
        loadAhead(bookId)
        if (pending.isEmpty()) {
          endOfBook = true
          if (inFlight.isEmpty()) {
            keepAlive.release()
            _state.value = _state.value.copy(isPlaying = false)
          }
          return
        }
      }
      val chunk = pending.removeFirst()
      val id = "$generation:${sequence++}"
      inFlight[id] = chunk
      engine.speak(id, chunk.text, flush = flushFirst && inFlight.size == 1)
    }
  }

  /** Reads the next window of paragraphs and chunks them. */
  private suspend fun loadAhead(bookId: String) {
    val state = _state.value
    val alreadyQueued = pending.lastOrNull() ?: inFlight.values.lastOrNull()
    val fromParagraph = alreadyQueued?.let {
      if (it.endOffset >= paragraphLength(bookId, it.paragraphIndex)) it.paragraphIndex + 1
      else it.paragraphIndex
    } ?: state.paragraphIndex
    val fromOffset = when {
      alreadyQueued == null -> state.charOffset
      alreadyQueued.endOffset >= paragraphLength(bookId, alreadyQueued.paragraphIndex) -> 0
      else -> alreadyQueued.endOffset
    }

    val rows = database.paragraphDao().range(bookId, fromParagraph, LOAD_WINDOW)
    if (rows.isEmpty()) return

    val maxChars = minOf(Chunker.DEFAULT_MAX_CHARS, engine.maxInputLength())
    rows.forEachIndexed { index, row ->
      val offset = if (index == 0) fromOffset else 0
      pending.addAll(Chunker.chunk(row.paragraphIndex, row.text, maxChars, offset))
    }
  }

  private val paragraphLengths = HashMap<Int, Int>()

  private suspend fun paragraphLength(bookId: String, index: Int): Int =
    paragraphLengths.getOrPut(index) {
      database.paragraphDao().at(bookId, index)?.text?.length ?: 0
    }

  private suspend fun persistProgress() {
    val current = _state.value
    val bookId = current.bookId ?: return
    database.progressDao().upsert(
      ProgressEntity(
        bookId = bookId,
        paragraphIndex = current.paragraphIndex,
        charOffset = current.charOffset,
        updatedAt = System.currentTimeMillis(),
      ),
    )
  }

  private fun chapterOf(chapters: List<ChapterEntity>, paragraphIndex: Int): Int {
    if (chapters.isEmpty()) return 0
    val index = chapters.indexOfLast { it.startParagraph <= paragraphIndex }
    return if (index < 0) 0 else index
  }

  private companion object {
    const val TAG = "Narrator"

    /** Utterances kept queued in the engine at once. */
    const val QUEUE_DEPTH = 3

    /** Paragraphs read from the database per top-up. */
    const val LOAD_WINDOW = 25

    const val MAX_CONSECUTIVE_FAILURES = 5

    /** Paragraphs into a chapter after which "previous" restarts it instead of going back. */
    const val RESTART_CHAPTER_THRESHOLD = 3
  }
}
