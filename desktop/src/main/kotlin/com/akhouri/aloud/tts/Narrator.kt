package com.akhouri.aloud.tts

import com.akhouri.aloud.AppPaths
import com.akhouri.aloud.audio.WavPlayer
import com.akhouri.aloud.data.BookDao
import com.akhouri.aloud.data.BookEntity
import com.akhouri.aloud.data.ChapterDao
import com.akhouri.aloud.data.ChapterEntity
import com.akhouri.aloud.data.HistoryEntry
import com.akhouri.aloud.data.ImportState
import com.akhouri.aloud.data.ListeningHistory
import com.akhouri.aloud.data.PlaceSuggestion
import com.akhouri.aloud.data.ParagraphDao
import com.akhouri.aloud.data.ProgressDao
import com.akhouri.aloud.data.ProgressEntity
import com.akhouri.aloud.util.CrashReporter
import java.io.File
import java.util.concurrent.Executors
import javax.sound.sampled.AudioSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** About 180 words a minute, the usual pace of a voice before any is measured. */
const val DEFAULT_CHARS_PER_SECOND = 15f

private const val MIN_PACE = 4f
private const val MAX_PACE = 60f
private const val PACE_WEIGHT = 0.08f

/** Everything the screens and the media controls need to know about narration. */
data class NarrationState(
  val bookId: String? = null,
  val bookTitle: String = "",
  val author: String? = null,
  val coverPath: String? = null,
  val chapters: List<ChapterEntity> = emptyList(),
  val chapterIndex: Int = 0,
  val chapterTitle: String? = null,
  val paragraphIndex: Int = 0,
  val charOffset: Int = 0,
  val paragraphCount: Int = 0,
  val isPlaying: Boolean = false,
  /** Waiting for the first words to be rendered. */
  val isPreparing: Boolean = false,
  /** Set when narration stopped for a reason the listener needs to see. */
  val error: String? = null,
  val speechRate: Float = 1.0f,
  /** How fast the voice really gets through text at normal speed, in characters a second. */
  val charsPerSecondAt1x: Float = DEFAULT_CHARS_PER_SECOND,
  val volume: Float = 1f,
) {
  val hasBook: Boolean get() = bookId != null
}

/**
 * Drives narration.
 *
 * Piper renders the book a stretch at a time into short audio files, staying a few files ahead
 * of the listener, and the player works through them in order. Every file carries a generation
 * stamp; a seek, a change of speed or a change of voice bumps the generation, and anything
 * rendered for the old position is recognised as stale and thrown away. The saved position is
 * always the start of the stretch being heard, so resuming repeats at most one stretch and never
 * skips one.
 */
class Narrator(
  private val speech: Speech,
  private val books: BookDao,
  private val chapterDao: ChapterDao,
  private val paragraphs: ParagraphDao,
  private val progress: ProgressDao,
  private val player: WavPlayer,
  private val scope: CoroutineScope,
) {

  private val _state = MutableStateFlow(NarrationState())
  val state: StateFlow<NarrationState> = _state.asStateFlow()

  private val mutex = Mutex()
  private val audio = Executors.newSingleThreadExecutor { task ->
    Thread(task, "aloud-audio").apply { isDaemon = true }
  }.asCoroutineDispatcher()

  private val speechDir: File = AppPaths.speech.apply {
    // Anything left from a previous run belongs to nothing now.
    listFiles()?.forEach { it.delete() }
  }

  /** Paragraphs to step back when resuming after a pause; set from settings. */
  @Volatile
  var rewindOnResume: Int = 0

  /** Where the listener has been in each book; set by the app graph. */
  @Volatile
  var history: ListeningHistory? = null

  /** The chosen voice's key (see [Voice.key]); null means the default. Set from settings. */
  @Volatile
  var voicePath: String? = null

  @Volatile
  private var generation = 0
  private var pipeline: Job? = null
  private var pipelineLive = false
  private var resumingFromPause = false

  /** The sleep timer's fade, multiplied into the listener's own volume. */
  private var fade = 1f

  private class Rendered(val chunk: SpeechChunk, val file: File, val rate: Float)

  // ---- listening history --------------------------------------------------------------------

  /**
   * The app has been brought up. If narration ran on a long way since the listener last did
   * anything, they may have fallen asleep to it: offer the way back.
   */
  suspend fun noteAppOpened() {
    val history = history ?: return
    val now = _state.value
    val bookId = now.bookId ?: return
    val last = history.latestAwake(bookId)
    if (last != null &&
      System.currentTimeMillis() - last.time >= ListeningHistory.DRIFT_MS &&
      now.paragraphIndex > last.paragraphIndex &&
      (now.chapterIndex != last.chapterIndex ||
        now.paragraphIndex - last.paragraphIndex >= ListeningHistory.DRIFT_PARAGRAPHS)
    ) {
      history.suggest(PlaceSuggestion(bookId, last, now.chapterIndex, now.chapterTitle))
    }
    note(ListeningHistory.OPENED_APP)
  }

  private suspend fun note(reason: String, awake: Boolean = true) {
    val history = history ?: return
    val at = _state.value
    val bookId = at.bookId ?: return
    val text = runCatching { paragraphs.range(bookId, at.paragraphIndex, 1).firstOrNull()?.text }.getOrNull().orEmpty()
    history.record(
      bookId,
      HistoryEntry(
        time = System.currentTimeMillis(),
        paragraphIndex = at.paragraphIndex,
        charOffset = at.charOffset,
        chapterIndex = at.chapterIndex,
        chapterTitle = at.chapterTitle,
        reason = reason,
        snippet = snippetOf(text, at.charOffset),
        awake = awake,
      ),
    )
  }

  private fun snippetOf(text: String, offset: Int): String {
    val start = offset.coerceIn(0, text.length)
    val rest = text.substring(start).trim()
    return if (rest.length <= 90) rest else rest.take(90).substringBeforeLast(' ') + "…"
  }

  // ---- control ------------------------------------------------------------------------------

  /** Loads a book and positions narration, without starting it. */
  suspend fun open(bookId: String, paragraphIndex: Int? = null, charOffset: Int = 0) {
    mutex.withLock { openLocked(bookId, paragraphIndex, charOffset) }
  }

  private suspend fun openLocked(bookId: String, paragraphIndex: Int?, charOffset: Int): Boolean {
    val book = books.find(bookId) ?: return false
    val chapters = chapterDao.forBook(bookId)
    val saved = progress.find(bookId)

    val startParagraph = paragraphIndex ?: saved?.paragraphIndex ?: 0
    val startOffset = if (paragraphIndex != null) charOffset else saved?.charOffset ?: 0

    stopPipeline()
    resumingFromPause = false
    val chapter = chapterOf(chapters, startParagraph)
    _state.value = _state.value.copy(
      bookId = bookId,
      bookTitle = book.title,
      author = book.author,
      coverPath = book.coverPath,
      chapters = chapters,
      chapterIndex = chapter,
      chapterTitle = chapters.getOrNull(chapter)?.title,
      paragraphIndex = startParagraph,
      charOffset = startOffset,
      paragraphCount = book.paragraphCount,
      isPlaying = false,
      isPreparing = false,
      error = null,
    )
    books.markOpened(bookId, System.currentTimeMillis())
    return true
  }

  /** Picks up a changed title, author or cover of the book that is open. */
  suspend fun refreshBookDetails() {
    val id = _state.value.bookId ?: return
    val book = books.find(id) ?: return
    _state.value = _state.value.copy(bookTitle = book.title, author = book.author, coverPath = book.coverPath)
  }

  suspend fun play() {
    mutex.withLock {
      val current = _state.value
      if (current.bookId == null) return

      val rewind = resumingFromPause && rewindOnResume > 0
      resumingFromPause = false
      note(ListeningHistory.PRESSED_PLAY)

      // Paused with a playlist still in hand: carry on from the very word it stopped at.
      if (pipelineLive && !rewind) {
        player.resume()
        _state.value = _state.value.copy(isPlaying = true, error = null)
        return
      }

      val voice = prepare() ?: return
      if (rewind) {
        val chapterStart = current.chapters.getOrNull(current.chapterIndex)?.startParagraph ?: 0
        val target = (current.paragraphIndex - rewindOnResume).coerceAtLeast(chapterStart)
        _state.value = _state.value.copy(paragraphIndex = target, charOffset = 0)
      }
      _state.value = _state.value.copy(isPlaying = true, isPreparing = true, error = null)
      startPipeline(voice)
      player.resume()
    }
  }

  /** Pauses. [automatic] is for pauses nobody asked for just then, like the sleep timer's. */
  suspend fun pause(automatic: Boolean = false) {
    mutex.withLock {
      if (!_state.value.hasBook) return
      if (automatic) note(ListeningHistory.SLEEP_TIMER, awake = false) else note(ListeningHistory.PAUSED)
      player.pause()
      resumingFromPause = pipelineLive
      _state.value = _state.value.copy(isPlaying = false, isPreparing = false)
      persistProgress()
    }
  }

  suspend fun togglePlayPause() {
    if (_state.value.isPlaying) pause() else play()
  }

  /** Stops and lets go of everything rendered; the next play starts from the saved place. */
  suspend fun stop() {
    mutex.withLock {
      player.pause()
      stopPipeline()
      _state.value = _state.value.copy(isPlaying = false, isPreparing = false)
      persistProgress()
    }
  }

  /** Jumps to an exact position: what a search result or a bookmark does. */
  suspend fun seekTo(paragraphIndex: Int, charOffset: Int = 0) {
    mutex.withLock {
      val current = _state.value
      if (current.bookId == null) return
      val target = paragraphIndex.coerceIn(0, (current.paragraphCount - 1).coerceAtLeast(0))
      val chapter = chapterOf(current.chapters, target)
      // Remember where this jump left from, so it can be undone from the history.
      if (kotlin.math.abs(target - current.paragraphIndex) > 2) note(ListeningHistory.BEFORE_JUMP)
      resumingFromPause = false
      _state.value = current.copy(
        paragraphIndex = target,
        charOffset = charOffset,
        chapterIndex = chapter,
        chapterTitle = current.chapters.getOrNull(chapter)?.title,
        error = null,
      )
      persistProgress()
      restartIfLive()
    }
  }

  suspend fun seekToChapter(chapterIndex: Int) {
    val chapters = _state.value.chapters
    val chapter = chapters.getOrNull(chapterIndex.coerceIn(0, chapters.lastIndex.coerceAtLeast(0))) ?: return
    seekTo(chapter.startParagraph)
  }

  suspend fun nextChapter() = seekToChapter(_state.value.chapterIndex + 1)

  /** Back a chapter, or to the start of this one if narration is some way into it. */
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

  suspend fun skipParagraphs(delta: Int) = seekTo(_state.value.paragraphIndex + delta)

  suspend fun setSpeechRate(rate: Float) {
    mutex.withLock {
      val clamped = (Math.round(rate * 20f) / 20f).coerceIn(PiperEngine.MIN_RATE, PiperEngine.MAX_RATE)
      if (clamped == _state.value.speechRate) return
      _state.value = _state.value.copy(speechRate = clamped)
      // Speed is part of how each file was rendered, so what is waiting is rendered again.
      restartIfLive()
    }
  }

  /** Switches the voice without losing the reading position. */
  suspend fun useVoice(path: String?) {
    mutex.withLock {
      if (path == voicePath) return
      voicePath = path
      restartIfLive()
      // The engine no longer needed lets its model go.
      scope.launch { runCatching { speech.keepOnly(Voices.resolve(path)) } }
    }
  }

  fun setVolume(volume: Float) {
    val clamped = volume.coerceIn(0f, 1f)
    _state.value = _state.value.copy(volume = clamped)
    player.volume = clamped * fade
  }

  /** The sleep timer's fade, from 0 to 1. */
  fun setFade(value: Float) {
    fade = value.coerceIn(0f, 1f)
    player.volume = _state.value.volume * fade
  }

  fun setLearnedPace(charsPerSecond: Float) {
    _state.value = _state.value.copy(charsPerSecondAt1x = charsPerSecond.coerceIn(MIN_PACE, MAX_PACE))
  }

  fun setInitialSpeechRate(rate: Float) {
    _state.value = _state.value.copy(speechRate = rate.coerceIn(PiperEngine.MIN_RATE, PiperEngine.MAX_RATE))
  }

  /** Saves the place and lets everything go; for when the app closes. */
  fun shutdown() {
    runCatching {
      runBlocking {
        player.pause()
        persistProgress()
      }
    }
    generation++
    pipeline?.cancel()
    runCatching { runBlocking { speech.stop() } }
    player.close()
    speechDir.listFiles()?.forEach { it.delete() }
  }

  // ---- pipeline -----------------------------------------------------------------------------

  /** Checks the speech engine and a voice are there. Reports and returns null if not. */
  private fun prepare(): Voice? {
    val voice = Voices.resolve(voicePath)
    val problem = if (voice == null) "No voice is installed. Add one in Settings, under Voice." else speech.problemWith(voice)
    if (problem != null) {
      _state.value = _state.value.copy(isPlaying = false, isPreparing = false, error = problem)
      return null
    }
    return voice
  }

  private fun stopPipeline() {
    generation++
    pipeline?.cancel()
    pipeline = null
    pipelineLive = false
    player.discard()
    speechDir.listFiles()?.forEach { it.delete() }
  }

  private fun startPipeline(voice: Voice) {
    stopPipeline()
    pipelineLive = true
    val stamp = generation
    val start = _state.value
    val queue = Channel<Rendered>(RENDER_AHEAD)
    pipeline = scope.launch {
      val producer = launch {
        try {
          produce(stamp, start, voice, queue)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          CrashReporter.record(e, "rendering speech")
          if (stamp == generation) failWith("Narration stopped unexpectedly (${e.javaClass.simpleName}). Your place was saved.")
        } finally {
          queue.close()
        }
      }
      try {
        consume(stamp, queue)
      } finally {
        producer.cancel()
      }
    }
  }

  /** After a seek or a change of speed or voice: render afresh if there is anything to replace. */
  private fun restartIfLive() {
    if (!pipelineLive) return
    val voice = prepare() ?: run {
      stopPipeline()
      return
    }
    if (_state.value.isPlaying) _state.value = _state.value.copy(isPreparing = true)
    startPipeline(voice)
  }

  private suspend fun produce(stamp: Int, start: NarrationState, voice: Voice, queue: Channel<Rendered>) {
    val bookId = start.bookId ?: return
    var nextParagraph = start.paragraphIndex
    var firstOffset = start.charOffset
    var first = true
    var failures = 0
    val pending = ArrayDeque<SpeechChunk>()

    while (stamp == generation) {
      if (pending.isEmpty()) {
        val rows = paragraphs.range(bookId, nextParagraph, LOAD_WINDOW)
        if (stamp != generation) return
        if (rows.isEmpty()) break
        rows.forEachIndexed { index, row ->
          // The first stretch is kept short so the voice starts sooner.
          val limit = if (first && index == 0) FIRST_CHUNK_CHARS else Chunker.DEFAULT_MAX_CHARS
          pending.addAll(Chunker.chunk(row.paragraphIndex, row.text, limit, if (index == 0) firstOffset else 0))
        }
        first = false
        firstOffset = 0
        nextParagraph = rows.last().paragraphIndex + 1
        continue
      }

      val chunk = pending.removeFirst()
      val rate = _state.value.speechRate
      val file = speech.synthesize(chunk.text, voice, rate, speechDir)
      if (stamp != generation) {
        file?.delete()
        return
      }
      if (file == null) {
        failures++
        if (failures >= MAX_CONSECUTIVE_FAILURES) {
          val detail = speech.lastError?.let { " ($it)" }.orEmpty()
          failWith("The speech engine kept failing$detail. Try another voice in Settings.")
          return
        }
        continue
      }
      failures = 0
      queue.send(Rendered(chunk, file, rate))
    }
  }

  private suspend fun consume(stamp: Int, queue: Channel<Rendered>) {
    for (item in queue) {
      if (stamp != generation) {
        item.file.delete()
        continue
      }
      val chapters = _state.value.chapters
      val chapter = chapterOf(chapters, item.chunk.paragraphIndex)
      _state.value = _state.value.copy(
        paragraphIndex = item.chunk.paragraphIndex,
        charOffset = item.chunk.startOffset,
        chapterIndex = chapter,
        chapterTitle = chapters.getOrNull(chapter)?.title,
        isPreparing = false,
      )
      persistProgress()
      learnPace(item)

      withContext(audio) { player.play(item.file) { stamp != generation } }
      item.file.delete()
      if (stamp != generation) return
    }
    if (stamp != generation) return

    // Everything has been written to the speakers; let the last of it be heard.
    withContext(audio) { player.drain { stamp != generation } }
    if (stamp != generation) return
    mutex.withLock { finishBook(stamp) }
  }

  /**
   * The last word of a book has been heard. A book in a collection hands over to the one after
   * it, so a collection plays through like one anthology; otherwise narration stops here.
   */
  private suspend fun finishBook(stamp: Int) {
    if (stamp != generation) return
    _state.value.bookId?.let { books.setCompleted(it, true) }

    val next = nextInCollection()
    if (next != null && openLocked(next.id, paragraphIndex = 0, charOffset = 0)) {
      val voice = prepare() ?: return
      _state.value = _state.value.copy(isPlaying = true, isPreparing = true)
      startPipeline(voice)
      player.resume()
      return
    }
    stopPipeline()
    _state.value = _state.value.copy(isPlaying = false, isPreparing = false)
  }

  private suspend fun nextInCollection(): BookEntity? {
    val currentId = _state.value.bookId ?: return null
    val collectionId = books.find(currentId)?.collectionId ?: return null
    val members = books.inCollection(collectionId)
    val index = members.indexOfFirst { it.id == currentId }
    if (index < 0) return null
    return members.drop(index + 1).firstOrNull { it.importState == ImportState.READY && it.paragraphCount > 0 }
  }

  private fun failWith(message: String) {
    player.pause()
    stopPipeline()
    _state.value = _state.value.copy(isPlaying = false, isPreparing = false, error = message)
  }

  /** Folds one rendered stretch into the running estimate of the voice's pace. */
  private fun learnPace(item: Rendered) {
    val seconds = runCatching {
      val format = AudioSystem.getAudioFileFormat(item.file)
      format.frameLength / format.format.frameRate
    }.getOrNull() ?: return
    val chars = item.chunk.text.length
    if (seconds < 0.5f || chars < 40 || item.rate <= 0f) return
    val measured = chars / seconds / item.rate
    if (measured !in MIN_PACE..MAX_PACE) return
    val current = _state.value.charsPerSecondAt1x
    _state.value = _state.value.copy(charsPerSecondAt1x = current * (1f - PACE_WEIGHT) + measured * PACE_WEIGHT)
  }

  private suspend fun persistProgress() {
    val current = _state.value
    val bookId = current.bookId ?: return
    runCatching {
      progress.upsert(ProgressEntity(bookId, current.paragraphIndex, current.charOffset, System.currentTimeMillis()))
    }
  }

  private fun chapterOf(chapters: List<ChapterEntity>, paragraphIndex: Int): Int {
    if (chapters.isEmpty()) return 0
    val index = chapters.indexOfLast { it.startParagraph <= paragraphIndex }
    return if (index < 0) 0 else index
  }

  private companion object {
    const val RENDER_AHEAD = 4
    const val LOAD_WINDOW = 25
    const val FIRST_CHUNK_CHARS = 160
    const val MAX_CONSECUTIVE_FAILURES = 4
    const val RESTART_CHAPTER_THRESHOLD = 3
  }
}
