package com.akhouri.aloud

import android.app.Application
import android.content.Context
import com.akhouri.aloud.data.AloudDatabase
import com.akhouri.aloud.data.SettingsStore
import com.akhouri.aloud.importer.BookImporter
import com.akhouri.aloud.playback.SleepTimer
import com.akhouri.aloud.tts.Narrator
import com.akhouri.aloud.tts.TtsEngine
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
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
   */
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  val database: AloudDatabase by lazy { AloudDatabase.build(appContext) }

  val settings: SettingsStore by lazy { SettingsStore(appContext) }

  val importer: BookImporter by lazy { BookImporter(appContext, database) }

  val ttsEngine: TtsEngine by lazy { TtsEngine(appContext) }

  val narrator: Narrator by lazy { Narrator(ttsEngine, database, scope) }

  val sleepTimer: SleepTimer by lazy { SleepTimer(narrator, scope) }

  /** Applies saved voice settings once, at startup, before anything is played. */
  fun restoreSettings() {
    scope.launch {
      val saved = settings.settings.first()
      narrator.setSpeechRate(saved.speechRate)
      narrator.setPitch(saved.pitch)
    }
  }
}

class AloudApp : Application() {

  lateinit var graph: AppGraph
    private set

  override fun onCreate() {
    super.onCreate()
    // PdfBox ships its fonts and resources as Android assets and will not parse without this.
    PDFBoxResourceLoader.init(this)
    graph = AppGraph(this)
    graph.restoreSettings()
  }
}

/** The graph, from anywhere that has a [Context]. */
val Context.graph: AppGraph
  get() = (applicationContext as AloudApp).graph
