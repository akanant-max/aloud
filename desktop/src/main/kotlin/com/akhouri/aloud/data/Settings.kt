package com.akhouri.aloud.data

import java.io.File
import java.util.Properties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { FollowSystem, Light, Dark }

/** [Accent] follows the accent colour chosen in Windows' personalisation settings. */
enum class ThemeColorScheme { Blue, Accent, Lagoon, Forest, Sunset, Berry, Lavender }

enum class LibrarySort { Recent, Title, Author, Added, Progress }

enum class LibraryView { List, Grid }

data class Settings(
  val speechRate: Float = 1.0f,
  /** Path of the chosen voice model; null means the voice that ships with the app. */
  val voicePath: String? = null,
  /** Voices put away from the list (keys, see Voice.key), shown again on request. */
  val hiddenVoices: Set<String> = emptySet(),
  val themeMode: ThemeMode = ThemeMode.FollowSystem,
  val themeColorScheme: ThemeColorScheme = ThemeColorScheme.Blue,
  val pureBlack: Boolean = false,
  /** Paragraphs a rewind or fast-forward moves by. */
  val skipParagraphs: Int = 1,
  /** Paragraphs to step back when narration resumes after a pause. */
  val rewindOnResume: Int = 0,
  val librarySort: LibrarySort = LibrarySort.Recent,
  val librarySortReversed: Boolean = false,
  val libraryView: LibraryView = LibraryView.List,
  /** Windows' media controls: the volume flyout, media keys and Bluetooth buttons. */
  val mediaControls: Boolean = true,
  /** Closing the window keeps Aloud running in the notification area. */
  val closeToTray: Boolean = true,
  /** The narration pace learned so far, in characters a second at normal speed. */
  val charsPerSecond: Float = 15f,
  val volume: Float = 1f,
  val readerTextSize: Int = 20,
  val autoSleep: Boolean = false,
  val autoSleepStartMinute: Int = 22 * 60,
  val autoSleepEndMinute: Int = 6 * 60,
  val sleepMinutes: Int = 30,
  val windowX: Int? = null,
  val windowY: Int? = null,
  val windowWidth: Int = 1180,
  val windowHeight: Int = 800,
  val windowMaximized: Boolean = false,
)

/** Settings kept as a small properties file, written whenever something changes. */
class SettingsStore(private val file: File) {

  private val _settings = MutableStateFlow(load())
  val settings: StateFlow<Settings> = _settings.asStateFlow()

  val current: Settings get() = _settings.value

  @Synchronized
  fun update(change: (Settings) -> Settings) {
    val next = change(_settings.value)
    if (next == _settings.value) return
    _settings.value = next
    runCatching { save(next) }
  }

  private fun load(): Settings {
    val p = Properties()
    runCatching { if (file.exists()) file.inputStream().use(p::load) }
    val d = Settings()
    fun str(key: String) = p.getProperty(key)
    fun float(key: String, default: Float) = str(key)?.toFloatOrNull() ?: default
    fun int(key: String, default: Int) = str(key)?.toIntOrNull() ?: default
    fun bool(key: String, default: Boolean) = str(key)?.toBooleanStrictOrNull() ?: default
    return Settings(
      speechRate = float("speechRate", d.speechRate),
      voicePath = str("voicePath")?.takeIf { it.isNotBlank() },
      hiddenVoices = str("hiddenVoices")?.split('|')?.filter { it.isNotBlank() }?.toSet() ?: d.hiddenVoices,
      themeMode = enumOr(str("themeMode"), d.themeMode),
      themeColorScheme = enumOr(str("themeColorScheme"), d.themeColorScheme),
      pureBlack = bool("pureBlack", d.pureBlack),
      skipParagraphs = int("skipParagraphs", d.skipParagraphs),
      rewindOnResume = int("rewindOnResume", d.rewindOnResume),
      librarySort = enumOr(str("librarySort"), d.librarySort),
      librarySortReversed = bool("librarySortReversed", d.librarySortReversed),
      libraryView = enumOr(str("libraryView"), d.libraryView),
      mediaControls = bool("mediaControls", d.mediaControls),
      closeToTray = bool("closeToTray", d.closeToTray),
      charsPerSecond = float("charsPerSecond", d.charsPerSecond),
      volume = float("volume", d.volume),
      readerTextSize = int("readerTextSize", d.readerTextSize),
      autoSleep = bool("autoSleep", d.autoSleep),
      autoSleepStartMinute = int("autoSleepStartMinute", d.autoSleepStartMinute),
      autoSleepEndMinute = int("autoSleepEndMinute", d.autoSleepEndMinute),
      sleepMinutes = int("sleepMinutes", d.sleepMinutes),
      windowX = str("windowX")?.toIntOrNull(),
      windowY = str("windowY")?.toIntOrNull(),
      windowWidth = int("windowWidth", d.windowWidth),
      windowHeight = int("windowHeight", d.windowHeight),
      windowMaximized = bool("windowMaximized", d.windowMaximized),
    )
  }

  private fun save(s: Settings) {
    val p = Properties()
    fun put(key: String, value: Any?) {
      if (value != null) p.setProperty(key, value.toString())
    }
    put("speechRate", s.speechRate)
    put("voicePath", s.voicePath)
    put("hiddenVoices", s.hiddenVoices.joinToString("|"))
    put("themeMode", s.themeMode.name)
    put("themeColorScheme", s.themeColorScheme.name)
    put("pureBlack", s.pureBlack)
    put("skipParagraphs", s.skipParagraphs)
    put("rewindOnResume", s.rewindOnResume)
    put("librarySort", s.librarySort.name)
    put("librarySortReversed", s.librarySortReversed)
    put("libraryView", s.libraryView.name)
    put("mediaControls", s.mediaControls)
    put("closeToTray", s.closeToTray)
    put("charsPerSecond", s.charsPerSecond)
    put("volume", s.volume)
    put("readerTextSize", s.readerTextSize)
    put("autoSleep", s.autoSleep)
    put("autoSleepStartMinute", s.autoSleepStartMinute)
    put("autoSleepEndMinute", s.autoSleepEndMinute)
    put("sleepMinutes", s.sleepMinutes)
    put("windowX", s.windowX)
    put("windowY", s.windowY)
    put("windowWidth", s.windowWidth)
    put("windowHeight", s.windowHeight)
    put("windowMaximized", s.windowMaximized)

    // Written beside the real file and moved over it, so a crash mid-write cannot corrupt it.
    val temp = File(file.parentFile, file.name + ".tmp")
    temp.outputStream().use { p.store(it, "Aloud settings") }
    if (!temp.renameTo(file)) {
      file.delete()
      temp.renameTo(file)
    }
  }

  private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
    name?.let { n -> runCatching { enumValueOf<T>(n) }.getOrNull() } ?: default
}
