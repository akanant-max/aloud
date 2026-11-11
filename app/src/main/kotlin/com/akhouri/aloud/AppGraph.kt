package com.akhouri.aloud

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.akhouri.aloud.data.AloudDatabase
import com.akhouri.aloud.data.SettingsStore
import com.akhouri.aloud.importer.BookImporter
import com.akhouri.aloud.playback.PlaybackKeepAlive
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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

  val ttsEngine: TtsEngine by lazy { TtsEngine(appContext) }

  val keepAlive: PlaybackKeepAlive by lazy { PlaybackKeepAlive(appContext) }

  val narrator: Narrator by lazy { Narrator(ttsEngine, database, keepAlive, scope) }

  val sleepTimer: SleepTimer by lazy { SleepTimer(narrator, scope) }

  /** Applies saved voice settings once, at startup, before anything is played. */
  fun restoreSettings() {
    scope.launch {
      val saved = settings.settings.first()
      narrator.setSpeechRate(saved.speechRate)
      narrator.setPitch(saved.pitch)
    }
  }

  /**
   * Puts the playback service in the foreground for as long as narration is running.
   *
   * Narration lives on this scope rather than inside the service, which leaves the service
   * doing nothing Android can see — and a background service doing nothing visible is the
   * first thing an aggressive vendor build reclaims. The audio carries on, because the process
   * is still alive, but the notification and its controls go with the service.
   *
   * Promoting it at the moment playback starts is also the only point where it is safe to do
   * so: a foreground start obliges the service to post its notification within a few seconds,
   * which Media3 can only honour once the player is genuinely playing. Starting it at launch
   * instead is what made an earlier build die on startup.
   *
   * Re-asserted on every transition into playing, so a service that was reclaimed while paused
   * comes back rather than staying gone for the rest of the session.
   */
  fun keepServiceForegroundWhilePlaying() {
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

    scope.launch {
      combine(narrator.state.map { it.isPlaying }.distinctUntilChanged(), enabled) { playing, on ->
        playing && on
      }
        .distinctUntilChanged()
        .collect { promote ->
          if (!promote) return@collect
          runCatching { ContextCompat.startForegroundService(appContext, intent) }
            .onFailure { CrashReporter.record(it, "promoting the playback service") }
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
    graph.restoreSettings()
    graph.keepServiceForegroundWhilePlaying()
  }
}

/** The graph, from anywhere that has a [Context]. */
val Context.graph: AppGraph
  get() = (applicationContext as AloudApp).graph
