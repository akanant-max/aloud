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

data class Settings(
  val speechRate: Float = 1.0f,
  val pitch: Float = 1.0f,
  val enginePackage: String? = null,
  val voiceName: String? = null,
  val themeMode: ThemeMode = ThemeMode.FollowSystem,
  val pureBlack: Boolean = false,
  /** Paragraphs a rewind or fast-forward moves by. */
  val skipParagraphs: Int = 1,
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
  }
}
