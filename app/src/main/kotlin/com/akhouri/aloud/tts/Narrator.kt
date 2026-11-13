package com.akhouri.aloud.tts

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import com.akhouri.aloud.data.AloudDatabase
import com.akhouri.aloud.data.entity.BookEntity
import com.akhouri.aloud.data.entity.ChapterEntity
import com.akhouri.aloud.data.entity.ImportState
import com.akhouri.aloud.data.entity.ProgressEntity
import com.akhouri.aloud.util.CrashReporter
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** About 180 words a minute, the usual pace of a speech engine before any is measured. */
const val DEFAULT_CHARS_PER_SECOND = 15f

private const val MIN_PACE = 4f
private const val MAX_PACE = 60f

/** How much one stretch of speech moves the estimate: enough to adapt, not enough to jitter. */
private const val PACE_WEIGHT = 0.08f

/** Everything the UI and the media session need to know about narration, in one place. */
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
  val isPreparing: Boolean = false,
  /** Set when narration stopped for a reason the user needs to see. */
  val error: String? = null,
  val speechRate: Float = 1.0f,
  val pitch: Float = 1.0f,
  /**
   * How fast the voice really gets through text at normal speed, in characters a second.
   * Measured from the audio it produces, so time estimates fit this voice on this phone.
   */
  val charsPerSecondAt1x: Float = DEFAULT_CHARS_PER_SECOND,
) {
  val hasBook: Boolean get() = bookId != null
}

/**
 * Drives narration.
 *
 * Speech is not spoken directly by the speech engine. The engine renders each stretch of text
 * to an audio file, and those files are played, in order, by an ordinary media player inside
 * this app. That detour is what makes narration behave like any other audio app: the sound
 * comes from this app, so Android routes headset, watch and car controls here; the player
 * handles audio focus, so narration pauses when something else plays and can be resumed from
 * its notification; and the media session reports real playback rather than an imitation.
 *
 * A producer keeps a few files rendered ahead of the listener. Every file carries a generation
 * stamp, so after a seek or a change of speed whatever was rendered for the old position is
 * recognised as stale and dropped. The saved position is always the start of the stretch now
 * playing, so resuming repeats at most one stretch and never skips one.
 */
class Narrator(
  context: Context,
  private val engine: TtsEngine,
  private val database: AloudDatabase,
  private val player: ExoPlayer,
  private val scope: CoroutineScope,
) {

  private val _state = MutableStateFlow(NarrationState())
  val state: StateFlow<NarrationState> = _state.asStateFlow()

  private val mutex = Mutex()

  private val audioDir = File(context.cacheDir, "speech").apply {
    // Anything left from a previous run belongs to no playlist and is only taking up room.
    listFiles()?.forEach { it.delete() }
    mkdirs()
  }

  /** Paragraphs to step back when resuming after a pause; set from settings. */
  @Volatile
  var rewindOnResume: Int = 0

  /** The speech engine and voice chosen in settings; null means the default. */
  @Volatile
  var enginePackage: String? = null

  @Volatile
  var voiceName: String? = null

  /** Bumped whenever the playlist is thrown away; files stamped with an older one are stale. */
  private var generation = 0
  private var sequence = 0

  private var producer: Job? = null

  /** True from the moment a playlist is started until it is thrown away. */
  private var pipelineLive = false

  /** The producer has rendered everything up to the end of the book. */
  private var producerDone = false

  /** Nudges the producer when the listener has moved on and there is room to render more. */
  private val room = Channel<Unit>(Channel.CONFLATED)

  private class Rendered(val chunk: SpeechChunk, val file: File, val rate: Float)

  private val rendered = HashMap<String, Rendered>()
  private var lastMeasuredId: String? = null
  private var resumingFromPause = false
  private var consecutiveFailures = 0

  private val listener = object : Player.Listener {

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
      val id = mediaItem?.mediaId ?: return
      val item = rendered[id] ?: return
      if (!id.startsWith("$generation:")) return

      val chapters = _state.value.chapters
      val chapter = chapterOf(chapters, item.chunk.paragraphIndex)
      _state.value = _state.value.copy(
        paragraphIndex = item.chunk.paragraphIndex,
        charOffset = item.chunk.startOffset,
        chapterIndex = chapter,
        chapterTitle = chapters.getOrNull(chapter)?.title,
        isPreparing = false,
      )

      // What has been listened to is dropped from the playlist and from disk.
      val index = player.currentMediaItemIndex
      if (index > 0) {
        for (i in 0 until index) {
          rendered.remove(player.getMediaItemAt(i).mediaId)?.file?.delete()
        }
        player.removeMediaItems(0, index)
      }

      scope.launch { persistProgress() }
      room.trySend(Unit)
      learnPace()
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
      learnPace()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
      if (playbackState == Player.STATE_READY) learnPace()
      // The last file has finished and nothing more is coming: the book is over.
      if (playbackState == Player.STATE_ENDED && producerDone) {
        val finished = generation
        scope.launch { mutex.withLock { finishBook(finished) } }
      }
    }

    /**
     * The one place the play state is read from. Whether it was this app's button, the
     * notification, a watch, or Android taking audio focus away for another app, the player
     * is what changed and the screen follows it.
     */
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
      if (!_state.value.hasBook) return
      _state.value = _state.value.copy(isPlaying = playWhenReady)
      if (!playWhenReady) {
        resumingFromPause = true
        scope.launch { persistProgress() }
      }
    }

    override fun onPlayerError(error: PlaybackException) {
      CrashReporter.record(error, "playing rendered speech")
      consecutiveFailures++
      if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
        failWith("Playback kept failing. Reopen the book to carry on; your place was saved.")
        return
      }
      // One unplayable file should not end the session: step over it.
      val next = player.currentMediaItemIndex + 1
      if (next < player.mediaItemCount) player.seekToDefaultPosition(next)
      player.prepare()
    }
  }

  init {
    player.addListener(listener)
  }

  // ---- public control ---------------------------------------------------------------------

  /** Loads a book and positions narration, without starting it. */
  suspend fun open(bookId: String, paragraphIndex: Int? = null, charOffset: Int = 0) {
    mutex.withLock { openLocked(bookId, paragraphIndex, charOffset) }
  }

  private suspend fun openLocked(bookId: String, paragraphIndex: Int?, charOffset: Int): Boolean {
    val book = database.bookDao().find(bookId) ?: return false
    val chapters = database.chapterDao().forBook(bookId)
    val saved = database.progressDao().find(bookId)

    val startParagraph = paragraphIndex ?: saved?.paragraphIndex ?: 0
    val startOffset = if (paragraphIndex != null) charOffset else saved?.charOffset ?: 0

    player.pause()
    resetPipeline()
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
    database.bookDao().markOpened(bookId, System.currentTimeMillis())
    return true
  }

  suspend fun play() {
    mutex.withLock {
      val current = _state.value
      if (current.bookId == null) return

      val rewind = resumingFromPause && rewindOnResume > 0
      resumingFromPause = false

      // Paused with a playlist still in hand: carry on from the very word it stopped at.
      if (pipelineLive && !rewind) {
        player.play()
        return
      }

      _state.value = current.copy(isPreparing = true, error = null)
      if (!prepareEngine()) return

      // Picking a book back up a little before where it stopped makes the thread easier to
      // find again. Only after a pause: a seek or a newly opened book starts where it says.
      if (rewind) {
        val chapterStart = current.chapters.getOrNull(current.chapterIndex)?.startParagraph ?: 0
        val target = (current.paragraphIndex - rewindOnResume).coerceAtLeast(chapterStart)
        _state.value = _state.value.copy(paragraphIndex = target, charOffset = 0)
      }

      startPipeline()
      player.play()
    }
  }

  suspend fun pause() {
    mutex.withLock {
      if (!_state.value.hasBook) return
      player.pause()
      _state.value = _state.value.copy(isPlaying = false, isPreparing = false)
      persistProgress()
    }
  }

  suspend fun togglePlayPause() {
    if (player.playWhenReady && _state.value.hasBook) pause() else play()
  }

  /** Pauses and lets go of everything rendered; the next play starts from the saved place. */
  suspend fun stop() {
    mutex.withLock {
      player.pause()
      resetPipeline()
      _state.value = _state.value.copy(isPlaying = false, isPreparing = false)
      persistProgress()
    }
  }

  /** Jumps to an exact position, which is what a search result or a bookmark does. */
  suspend fun seekTo(paragraphIndex: Int, charOffset: Int = 0) {
    mutex.withLock {
      val current = _state.value
      if (current.bookId == null) return
      val target = paragraphIndex.coerceIn(0, (current.paragraphCount - 1).coerceAtLeast(0))
      val chapter = chapterOf(current.chapters, target)

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
    val chapter = chapters.getOrNull(chapterIndex.coerceIn(0, chapters.lastIndex.coerceAtLeast(0)))
      ?: return
    seekTo(chapter.startParagraph)
  }

  suspend fun nextChapter() {
    seekToChapter(_state.value.chapterIndex + 1)
  }

  /**
   * Back a chapter, or to the start of this one if narration is already some way in, which is
   * what people expect of a previous-track button.
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
      if (clamped == _state.value.speechRate) return
      _state.value = _state.value.copy(speechRate = clamped)
      engine.setSpeechRate(clamped)
      // The speed is part of how each file was rendered, so what is waiting is rendered again.
      restartIfLive()
    }
  }

  suspend fun setPitch(pitch: Float) {
    mutex.withLock {
      val clamped = pitch.coerceIn(TtsEngine.MIN_PITCH, TtsEngine.MAX_PITCH)
      if (clamped == _state.value.pitch) return
      _state.value = _state.value.copy(pitch = clamped)
      engine.setPitch(clamped)
      restartIfLive()
    }
  }

  /** Switches the engine or voice without losing the reading position. */
  suspend fun useVoice(enginePackage: String?, voiceName: String?) {
    mutex.withLock {
      this.enginePackage = enginePackage
      this.voiceName = voiceName
      if (pipelineLive && prepareEngine()) restartIfLive()
    }
  }

  /** Restores the pace learned in earlier sessions. */
  fun setLearnedPace(charsPerSecond: Float) {
    _state.value = _state.value.copy(
      charsPerSecondAt1x = charsPerSecond.coerceIn(MIN_PACE, MAX_PACE),
    )
  }

  /** Playback volume from silent to full, used to fade out at the end of a sleep timer. */
  fun setVolume(volume: Float) {
    player.volume = volume.coerceIn(0f, 1f)
  }

  // ---- pipeline ---------------------------------------------------------------------------

  /** Starts the speech engine with the chosen voice. Reports and returns false if it cannot. */
  private suspend fun prepareEngine(): Boolean {
    val ready = engine.ensureReady(enginePackage ?: TtsEngine.GOOGLE_TTS)
    if (ready.isFailure) {
      _state.value = _state.value.copy(
        isPreparing = false,
        isPlaying = false,
        error = ready.exceptionOrNull()?.message
          ?: "No speech engine is available. Install Google Text-to-Speech, then try again.",
      )
      return false
    }
    engine.setSpeechRate(_state.value.speechRate)
    engine.setPitch(_state.value.pitch)
    voiceName?.let { engine.setVoice(it) }
    return true
  }

  /** Throws the playlist away. Whether the player should be playing is left untouched. */
  private fun resetPipeline() {
    generation++
    producer?.cancel()
    producer = null
    pipelineLive = false
    producerDone = false
    engine.stop()
    player.stop()
    player.clearMediaItems()
    rendered.values.forEach { it.file.delete() }
    rendered.clear()
    audioDir.listFiles()?.forEach { it.delete() }
  }

  /** Begins rendering from the current position. The engine must already be prepared. */
  private fun startPipeline() {
    resetPipeline()
    pipelineLive = true
    consecutiveFailures = 0
    val stamp = generation
    producer = scope.launch {
      try {
        produce(stamp)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        CrashReporter.record(e, "rendering speech")
        if (stamp == generation) {
          failWith("Narration stopped unexpectedly (${e.javaClass.simpleName}). Your place was saved.")
        }
      }
    }
  }

  /** After a seek or a change of voice: render afresh if there is a playlist to replace. */
  private fun restartIfLive() {
    if (pipelineLive || player.playWhenReady) {
      if (player.playWhenReady) _state.value = _state.value.copy(isPreparing = true)
      startPipeline()
    }
  }

  /** Renders the book stretch by stretch, staying a few files ahead of the listener. */
  private suspend fun produce(stamp: Int) {
    val start = _state.value
    val bookId = start.bookId ?: return
    var nextParagraph = start.paragraphIndex
    var firstOffset = start.charOffset
    val pending = ArrayDeque<SpeechChunk>()
    val maxChars = minOf(Chunker.DEFAULT_MAX_CHARS, engine.maxInputLength())

    while (stamp == generation) {
      // Wait while enough is already rendered ahead.
      while (player.mediaItemCount - player.currentMediaItemIndex.coerceAtLeast(0) >= RENDER_AHEAD) {
        room.receive()
        if (stamp != generation) return
      }

      if (pending.isEmpty()) {
        val rows = database.paragraphDao().range(bookId, nextParagraph, LOAD_WINDOW)
        if (stamp != generation) return
        if (rows.isEmpty()) break
        rows.forEachIndexed { index, row ->
          val offset = if (index == 0) firstOffset else 0
          pending.addAll(Chunker.chunk(row.paragraphIndex, row.text, maxChars, offset))
        }
        firstOffset = 0
        nextParagraph = rows.last().paragraphIndex + 1
        continue
      }

      val chunk = pending.removeFirst()
      val id = "$stamp:${sequence++}"
      val file = File(audioDir, "$stamp-$sequence.wav")
      val rate = _state.value.speechRate
      val ok = engine.synthesize(id, chunk.text, file)
      if (stamp != generation) {
        file.delete()
        return
      }
      if (!ok) {
        file.delete()
        consecutiveFailures++
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
          failWith("The speech engine kept failing. Check its voice data is installed, then try again.")
          return
        }
        continue
      }
      consecutiveFailures = 0
      // Nothing speakable in it, such as a row of asterisks: not an error, just nothing to play.
      if (!file.exists() || file.length() <= WAV_HEADER_BYTES) {
        file.delete()
        continue
      }

      rendered[id] = Rendered(chunk, file, rate)
      player.addMediaItem(mediaItemFor(id, file, chunk))
      when (player.playbackState) {
        Player.STATE_IDLE -> player.prepare()
        // The listener caught up with the renderer; pick up again at the new file.
        Player.STATE_ENDED -> player.seekToDefaultPosition(player.mediaItemCount - 1)
        else -> Unit
      }
    }

    if (stamp != generation) return
    producerDone = true
    // Nothing left to play either, so the end of the book has already been reached.
    if (player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
      mutex.withLock { finishBook(stamp) }
    }
  }

  private fun mediaItemFor(id: String, file: File, chunk: SpeechChunk): MediaItem {
    val current = _state.value
    val chapter = chapterOf(current.chapters, chunk.paragraphIndex)
    val metadata = MediaMetadata.Builder()
      .setTitle(current.chapters.getOrNull(chapter)?.title ?: "Chapter ${chapter + 1}")
      .setArtist(current.author ?: current.bookTitle)
      .setAlbumTitle(current.bookTitle)
      .setArtworkUri(current.coverPath?.let { Uri.fromFile(File(it)) })
      .build()
    return MediaItem.Builder()
      .setMediaId(id)
      .setUri(Uri.fromFile(file))
      .setMediaMetadata(metadata)
      .build()
  }

  /**
   * The last word of a book has been spoken. A book filed in a collection hands over to the
   * one after it, so a collection plays through like a single anthology; otherwise narration
   * stops here.
   */
  private suspend fun finishBook(stamp: Int) {
    if (stamp != generation) return
    // Played through to its last word, so it counts as completed without being told.
    _state.value.bookId?.let { database.bookDao().setCompleted(it, true) }

    val next = nextInCollection()
    if (next != null && openLocked(next.id, paragraphIndex = 0, charOffset = 0)) {
      _state.value = _state.value.copy(isPreparing = true)
      startPipeline()
      player.play()
      return
    }

    player.pause()
    resetPipeline()
    _state.value = _state.value.copy(isPlaying = false, isPreparing = false)
  }

  private suspend fun nextInCollection(): BookEntity? {
    val currentId = _state.value.bookId ?: return null
    val collectionId = database.bookDao().find(currentId)?.collectionId ?: return null
    val books = database.bookDao().inCollection(collectionId)
    val index = books.indexOfFirst { it.id == currentId }
    if (index < 0) return null
    return books.drop(index + 1).firstOrNull {
      it.importState == ImportState.READY && it.paragraphCount > 0
    }
  }

  private fun failWith(message: String) {
    player.pause()
    resetPipeline()
    _state.value = _state.value.copy(isPlaying = false, isPreparing = false, error = message)
  }

  /**
   * Folds the file now playing into the running estimate of the voice's pace: its characters
   * over its length, divided by the speed it was rendered at. Keeping the figure a normal-speed
   * one means an estimate can be rescaled the moment the speed changes.
   */
  private fun learnPace() {
    val id = player.currentMediaItem?.mediaId ?: return
    if (id == lastMeasuredId) return
    val item = rendered[id] ?: return
    val durationMs = player.duration
    if (durationMs == C.TIME_UNSET) return
    lastMeasuredId = id

    val chars = item.chunk.text.length
    if (durationMs < 500 || chars < 40 || item.rate <= 0f) return
    val measured = chars / (durationMs / 1000f) / item.rate
    if (measured !in MIN_PACE..MAX_PACE) return

    val current = _state.value.charsPerSecondAt1x
    _state.value = _state.value.copy(
      charsPerSecondAt1x = current * (1f - PACE_WEIGHT) + measured * PACE_WEIGHT,
    )
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
    /** Files kept rendered ahead of the one playing. */
    const val RENDER_AHEAD = 4

    /** Paragraphs read from the database per top-up. */
    const val LOAD_WINDOW = 25

    const val MAX_CONSECUTIVE_FAILURES = 5

    /** Paragraphs into a chapter after which "previous" restarts it instead of going back. */
    const val RESTART_CHAPTER_THRESHOLD = 3

    /** A WAV file this size holds a header and no sound. */
    const val WAV_HEADER_BYTES = 64L
  }
}
