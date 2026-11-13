package com.akhouri.aloud

import android.app.Application
import android.content.Context
import android.content.Intent
import com.akhouri.aloud.data.AloudDatabase
import com.akhouri.aloud.data.SettingsStore
import com.akhouri.aloud.importer.BookImporter
import com.akhouri.aloud.importer.ImportQueue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import com.akhouri.aloud.playback.PlaybackService
import com.akhouri.aloud.playback.SleepTimer
import com.akhouri.aloud.tts.Narrator
import com.akhouri.aloud.tts.TtsEngine
import com.akhouri.aloud.util.CrashReporter
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Hand-rolled dependency graph.
 *
 * A single shared [Narrator] is the point: the screens and the media session both drive the same
 * object, so there is never a moment where the notification and the UI disagree about what is
 * playing. An annotation processor would buy nothing here and cost build time.
 */
class AppGraph(context: Context) {

  private val appContext = context.applicationContext

  /**
   * Lives as long as the process. Narration has to outlast any one screen, and outlast the
   * service being rebound, so it cannot hang off either.
   *
   * The handler matters more than it looks: without one, a single unexpected exception
   * anywhere in narration reaches the default handler and takes the whole app down. A failed
   * utterance should cost the listener their place at worst, never the process.
   */
  val scope = CoroutineScope(
    SupervisorJob() +
      Dispatchers.Main.immediate +
      CoroutineExceptionHandler { _, error -> CrashReporter.record(error, "narration") },
  )

  val database: AloudDatabase by lazy { AloudDatabase.build(appContext) }

  val settings: SettingsStore by lazy { SettingsStore(appContext) }

  val importer: BookImporter by lazy { BookImporter(appContext, database) }

  val importQueue: ImportQueue by lazy { ImportQueue(importer, database, scope) }

  val ttsEngine: TtsEngine by lazy { TtsEngine(appContext) }

  /**
   * The player that actually makes the sound. Because it is an ordinary media player in this
   * app's own process, it takes care of the things Android expects of one: audio focus, so it
   * pauses when another app plays; stopping when headphones are unplugged; and keeping the
   * processor awake while it plays.
   */
  val player: ExoPlayer by lazy {
    ExoPlayer.Builder(appContext)
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(C.USAGE_MEDIA)
          .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
          .build(),
        /* handleAudioFocus = */ true,
      )
      .setHandleAudioBecomingNoisy(true)
      .setWakeMode(C.WAKE_MODE_LOCAL)
      .build()
  }

  val narrator: Narrator by lazy { Narrator(appContext, ttsEngine, database, player, scope) }

  val sleepTimer: SleepTimer by lazy { SleepTimer(narrator, scope) }

  /** Nothing can be mid-import at startup, so anything marked that way was cut off. */
  fun recoverInterruptedImports() {
    scope.launch {
      database.bookDao().failInterrupted("The import was interrupted. Add the file again to retry.")
    }
  }

  /** Feeds the listening settings that narration consults as it runs. */
  fun followListeningSettings() {
    scope.launch {
      settings.settings.collect { saved ->
        narrator.rewindOnResume = saved.rewindOnResume
        narrator.enginePackage = saved.enginePackage
        narrator.voiceName = saved.voiceName
      }
    }
    // Narration that starts inside the chosen hours gets a sleep timer without being asked.
    scope.launch {
      narrator.state.map { it.isPlaying }.distinctUntilChanged().collect { playing ->
        if (!playing) return@collect
        val saved = settings.settings.first()
        if (!saved.autoSleep || sleepTimer.state.value.running) return@collect
        val now = java.util.Calendar.getInstance().let {
          it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE)
        }
        val start = saved.autoSleepStartMinute
        val end = saved.autoSleepEndMinute
        // The window may run past midnight, as from ten at night to six in the morning.
        val inside = if (start <= end) now in start until end else now >= start || now < end
        if (inside) sleepTimer.start(saved.sleepMinutes * 60_000L)
      }
    }
  }

  /** Applies saved voice settings once, at startup, before anything is played. */
  fun restoreSettings() {
    scope.launch {
      val saved = settings.settings.first()
      narrator.setSpeechRate(saved.speechRate)
      narrator.setPitch(saved.pitch)
      narrator.setLearnedPace(saved.charsPerSecond)

      // Saved in tenths, so the store is written when the pace has really moved rather than
      // after every sentence.
      narrator.state
        .map { (it.charsPerSecondAt1x * 10f).toInt() }
        .distinctUntilChanged()
        .drop(1)
        .collect { tenths -> settings.setCharsPerSecond(tenths / 10f) }
    }
  }

  /**
   * Keeps the playback service in step with the notification setting.
   *
   * The service is only ever started plainly. Entering the foreground is left to Media3, which
   * does it itself the moment the player it is watching starts playing; asking for a
   * foreground start from here as well meant a promise to post a notification that only
   * Media3 could keep, and the system ends the process when that promise is missed.
   */
  fun followNotificationSetting() {
    val enabled = settings.settings.map { it.mediaNotification }.distinctUntilChanged()
    val intent = Intent(appContext, PlaybackService::class.java)

    // Switching the notification off in settings takes the whole media session down with it,
    // since Media3 shows one for as long as a session is playing.
    scope.launch {
      enabled.collect { on ->
        if (on) {
          runCatching { appContext.startService(intent) }
        } else {
          runCatching { appContext.stopService(intent) }
        }
      }
    }
  }
}

class AloudApp : Application() {

  lateinit var graph: AppGraph
    private set

  override fun onCreate() {
    super.onCreate()
    // Installed first, so a failure during the rest of startup is still recorded.
    CrashReporter.install(this)
    // PdfBox ships its fonts and resources as Android assets and will not parse without this.
    PDFBoxResourceLoader.init(this)
    graph = AppGraph(this)
    graph.recoverInterruptedImports()
    graph.restoreSettings()
    graph.followNotificationSetting()
    graph.followListeningSettings()
  }
}

/** The graph, from anywhere that has a [Context]. */
val Context.graph: AppGraph
  get() = (applicationContext as AloudApp).graph
