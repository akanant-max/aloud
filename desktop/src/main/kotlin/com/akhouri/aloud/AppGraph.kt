package com.akhouri.aloud

import com.akhouri.aloud.audio.WavPlayer
import com.akhouri.aloud.data.BookDao
import com.akhouri.aloud.data.BookmarkDao
import com.akhouri.aloud.data.ChapterDao
import com.akhouri.aloud.data.CollectionDao
import com.akhouri.aloud.data.Database
import com.akhouri.aloud.data.ParagraphDao
import com.akhouri.aloud.data.ProgressDao
import com.akhouri.aloud.data.SettingsStore
import com.akhouri.aloud.importer.BookImporter
import com.akhouri.aloud.importer.ImportQueue
import com.akhouri.aloud.media.MediaControls
import com.akhouri.aloud.playback.SleepTimer
import com.akhouri.aloud.tts.Narrator
import com.akhouri.aloud.tts.Speech
import com.akhouri.aloud.util.CrashReporter
import java.util.Calendar
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Something the keyboard or the tray asks a screen to do. */
enum class AppCommand { AddBooks, Search, Settings }

/**
 * Everything the app is made of, created once. Screens, the tray and the media controls all
 * drive the same narrator, so they can never disagree about what is playing.
 */
class AppGraph {

  /** Lives as long as the app. A failure in it is recorded rather than allowed to end the app. */
  val scope = CoroutineScope(
    SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, error -> CrashReporter.record(error, "background") },
  )

  val database = Database(AppPaths.database)
  val books = BookDao(database)
  val chapters = ChapterDao(database)
  val paragraphs = ParagraphDao(database)
  val progress = ProgressDao(database)
  val bookmarks = BookmarkDao(database)
  val collections = CollectionDao(database)

  val settings = SettingsStore(AppPaths.settings)

  val importer = BookImporter(books, chapters, paragraphs)
  val importQueue = ImportQueue(importer, books, scope)

  val speech = Speech()
  val player = WavPlayer()
  val narrator = Narrator(speech, books, chapters, paragraphs, progress, player, scope)
  val sleepTimer = SleepTimer(narrator, scope)
  val mediaControls = MediaControls(narrator, { settings.current.skipParagraphs }, scope)

  private val _commands = MutableSharedFlow<AppCommand>(extraBufferCapacity = 8)
  val commands: SharedFlow<AppCommand> = _commands.asSharedFlow()

  fun send(command: AppCommand) {
    _commands.tryEmit(command)
  }

  fun start() {
    scope.launch { books.failInterrupted("The import was interrupted. Add the file again to retry.") }

    val saved = settings.current
    narrator.setInitialSpeechRate(saved.speechRate)
    narrator.setLearnedPace(saved.charsPerSecond)
    narrator.setVolume(saved.volume)
    narrator.voicePath = saved.voicePath
    narrator.rewindOnResume = saved.rewindOnResume

    scope.launch {
      settings.settings.collect { s ->
        narrator.rewindOnResume = s.rewindOnResume
        narrator.useVoice(s.voicePath)
      }
    }

    // Speed, volume and the learned pace are saved as they change.
    scope.launch {
      narrator.state.map { it.speechRate }.distinctUntilChanged().drop(1)
        .collect { rate -> settings.update { it.copy(speechRate = rate) } }
    }
    scope.launch {
      narrator.state.map { it.volume }.distinctUntilChanged().drop(1)
        .collect { volume -> settings.update { it.copy(volume = volume) } }
    }
    scope.launch {
      narrator.state.map { (it.charsPerSecondAt1x * 10f).toInt() }.distinctUntilChanged().drop(1)
        .collect { tenths -> settings.update { it.copy(charsPerSecond = tenths / 10f) } }
    }

    // Narration that starts inside the chosen hours gets a sleep timer without being asked.
    scope.launch {
      narrator.state.map { it.isPlaying }.distinctUntilChanged().collect { playing ->
        if (!playing) return@collect
        val s = settings.current
        if (!s.autoSleep || sleepTimer.state.value.running) return@collect
        val now = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        val start = s.autoSleepStartMinute
        val end = s.autoSleepEndMinute
        val inside = if (start <= end) now in start until end else now >= start || now < end
        if (inside) sleepTimer.start(s.sleepMinutes * 60_000L)
      }
    }

    mediaControls.start(settings.settings.map { it.mediaControls })
  }

  /** Saves the place and stops everything; for when the app is closing. */
  fun shutdown() {
    runCatching { mediaControls.shutdown() }
    runCatching { narrator.shutdown() }
  }
}
