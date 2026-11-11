package com.akhouri.aloud.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { FollowSystem, Light, Dark }

enum class ThemeColorScheme { Blue, Dynamic, Lagoon, Forest, Sunset, Berry, Lavender }

enum class LibrarySort { Recent, Title, Author, Added, Progress }

enum class LibraryView { List, Grid }

data class Settings(
  val speechRate: Float = 1.0f,
  val pitch: Float = 1.0f,
  val enginePackage: String? = null,
  val voiceName: String? = null,
  val themeMode: ThemeMode = ThemeMode.FollowSystem,
  val pureBlack: Boolean = false,
  /** Paragraphs a rewind or fast-forward moves by. */
  val skipParagraphs: Int = 1,
  val librarySort: LibrarySort = LibrarySort.Recent,
  val librarySortReversed: Boolean = false,
  val libraryView: LibraryView = LibraryView.List,
  /** Whether the media notification, and the lock-screen and headset controls, are on. */
  val mediaNotification: Boolean = true,
  /** The narration pace learned so far, in characters a second at normal speed. */
  val charsPerSecond: Float = 15f,
  val themeColorScheme: ThemeColorScheme = ThemeColorScheme.Blue,
  /** Paragraphs to step back when narration resumes after a pause, to pick the thread up. */
  val rewindOnResume: Int = 0,
  /** Starts a sleep timer by itself when narration begins inside the hours below. */
  val autoSleep: Boolean = false,
  val autoSleepStartMinute: Int = 22 * 60,
  val autoSleepEndMinute: Int = 6 * 60,
  val sleepMinutes: Int = 30,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {

  val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
    Settings(
      speechRate = prefs[SPEECH_RATE] ?: 1.0f,
      pitch = prefs[PITCH] ?: 1.0f,
      enginePackage = prefs[ENGINE],
      voiceName = prefs[VOICE],
      themeMode = prefs[THEME]?.let { name ->
        runCatching { ThemeMode.valueOf(name) }.getOrDefault(ThemeMode.FollowSystem)
      } ?: ThemeMode.FollowSystem,
      pureBlack = prefs[PURE_BLACK] ?: false,
      skipParagraphs = prefs[SKIP_PARAGRAPHS] ?: 1,
      librarySort = prefs[LIBRARY_SORT]?.let { name ->
        runCatching { LibrarySort.valueOf(name) }.getOrNull()
      } ?: LibrarySort.Recent,
      librarySortReversed = prefs[LIBRARY_SORT_REVERSED] ?: false,
      libraryView = prefs[LIBRARY_VIEW]?.let { name ->
        runCatching { LibraryView.valueOf(name) }.getOrNull()
      } ?: LibraryView.List,
      mediaNotification = prefs[MEDIA_NOTIFICATION] ?: true,
      charsPerSecond = prefs[CHARS_PER_SECOND] ?: 15f,
      themeColorScheme = prefs[COLOR_SCHEME]?.let { name ->
        runCatching { ThemeColorScheme.valueOf(name) }.getOrNull()
      } ?: ThemeColorScheme.Blue,
      rewindOnResume = prefs[REWIND_ON_RESUME] ?: 0,
      autoSleep = prefs[AUTO_SLEEP] ?: false,
      autoSleepStartMinute = prefs[AUTO_SLEEP_START] ?: 22 * 60,
      autoSleepEndMinute = prefs[AUTO_SLEEP_END] ?: 6 * 60,
      sleepMinutes = prefs[SLEEP_MINUTES] ?: 30,
    )
  }

  suspend fun setSpeechRate(rate: Float) = edit { it[SPEECH_RATE] = rate }

  suspend fun setPitch(pitch: Float) = edit { it[PITCH] = pitch }

  suspend fun setEngine(packageName: String?) = edit { prefs ->
    if (packageName == null) prefs.remove(ENGINE) else prefs[ENGINE] = packageName
  }

  suspend fun setVoice(voiceName: String?) = edit { prefs ->
    if (voiceName == null) prefs.remove(VOICE) else prefs[VOICE] = voiceName
  }

  suspend fun setThemeMode(mode: ThemeMode) = edit { it[THEME] = mode.name }

  suspend fun setPureBlack(enabled: Boolean) = edit { it[PURE_BLACK] = enabled }

  suspend fun setSkipParagraphs(count: Int) = edit { it[SKIP_PARAGRAPHS] = count.coerceIn(1, 20) }

  suspend fun setLibrarySort(sort: LibrarySort, reversed: Boolean) = edit {
    it[LIBRARY_SORT] = sort.name
    it[LIBRARY_SORT_REVERSED] = reversed
  }

  suspend fun setLibraryView(view: LibraryView) = edit { it[LIBRARY_VIEW] = view.name }

  suspend fun setMediaNotification(enabled: Boolean) = edit { it[MEDIA_NOTIFICATION] = enabled }

  suspend fun setCharsPerSecond(value: Float) = edit { it[CHARS_PER_SECOND] = value }

  suspend fun setThemeColorScheme(scheme: ThemeColorScheme) = edit { it[COLOR_SCHEME] = scheme.name }

  suspend fun setRewindOnResume(paragraphs: Int) = edit { it[REWIND_ON_RESUME] = paragraphs.coerceIn(0, 5) }

  suspend fun setAutoSleep(enabled: Boolean) = edit { it[AUTO_SLEEP] = enabled }

  suspend fun setAutoSleepStart(minuteOfDay: Int) = edit { it[AUTO_SLEEP_START] = minuteOfDay }

  suspend fun setAutoSleepEnd(minuteOfDay: Int) = edit { it[AUTO_SLEEP_END] = minuteOfDay }

  suspend fun setSleepMinutes(minutes: Int) = edit { it[SLEEP_MINUTES] = minutes.coerceIn(5, 180) }

  private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
    context.dataStore.edit(block)
  }

  private companion object {
    val SPEECH_RATE = floatPreferencesKey("speech_rate")
    val PITCH = floatPreferencesKey("pitch")
    val ENGINE = stringPreferencesKey("engine")
    val VOICE = stringPreferencesKey("voice")
    val THEME = stringPreferencesKey("theme_mode")
    val PURE_BLACK = booleanPreferencesKey("pure_black")
    val SKIP_PARAGRAPHS = intPreferencesKey("skip_paragraphs")
    val LIBRARY_SORT = stringPreferencesKey("library_sort")
    val LIBRARY_SORT_REVERSED = booleanPreferencesKey("library_sort_reversed")
    val LIBRARY_VIEW = stringPreferencesKey("library_view")
    val MEDIA_NOTIFICATION = booleanPreferencesKey("media_notification")
    val CHARS_PER_SECOND = floatPreferencesKey("chars_per_second")
    val COLOR_SCHEME = stringPreferencesKey("color_scheme")
    val REWIND_ON_RESUME = intPreferencesKey("rewind_on_resume")
    val AUTO_SLEEP = booleanPreferencesKey("auto_sleep")
    val AUTO_SLEEP_START = intPreferencesKey("auto_sleep_start")
    val AUTO_SLEEP_END = intPreferencesKey("auto_sleep_end")
    val SLEEP_MINUTES = intPreferencesKey("sleep_minutes")
  }
}
