package com.akhouri.aloud.playback

import com.akhouri.aloud.tts.Narrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Stops narration after a set time, or at the end of the chapter.
 *
 * The volume fades over the final stretch, so the end arrives gently rather than as a voice
 * cut off mid-sentence.
 */
class SleepTimer(
  private val narrator: Narrator,
  private val scope: CoroutineScope,
) {

  data class State(
    val running: Boolean = false,
    val remainingMs: Long = 0L,
    val untilEndOfChapter: Boolean = false,
  )

  private val _state = MutableStateFlow(State())
  val state: StateFlow<State> = _state.asStateFlow()

  private var job: Job? = null
  private var fading = false

  fun start(durationMs: Long) {
    cancel()
    job = scope.launch {
      var remaining = durationMs
      _state.value = State(running = true, remainingMs = remaining)

      while (isActive && remaining > 0) {
        delay(TICK_MS)
        remaining -= TICK_MS
        _state.value = _state.value.copy(remainingMs = remaining.coerceAtLeast(0))

        if (remaining in 1..WIND_DOWN_MS) {
          windDown(remaining)
        }
      }

      if (isActive) finish()
    }
  }

  /** Runs until the current chapter ends, then stops. */
  fun startUntilEndOfChapter() {
    cancel()
    val startingChapter = narrator.state.value.chapterIndex
    _state.value = State(running = true, untilEndOfChapter = true)

    job = scope.launch {
      narrator.state.first { narration ->
        narration.chapterIndex != startingChapter ||
          (!narration.isPlaying && !narration.isPreparing)
      }
      finish()
    }
  }

  fun cancel() {
    job?.cancel()
    job = null
    restoreVolume()
    _state.value = State()
  }

  /** Adds time without disturbing a timer already counting down. */
  fun extend(byMs: Long) {
    val current = _state.value
    if (!current.running || current.untilEndOfChapter) return
    start(current.remainingMs + byMs)
  }

  /** Fades the volume down over the last stretch, so the end is not a sudden silence. */
  private fun windDown(remainingMs: Long) {
    fading = true
    val fraction = (remainingMs.toFloat() / WIND_DOWN_MS).coerceIn(0f, 1f)
    narrator.setVolume(QUIETEST + (1f - QUIETEST) * fraction)
  }

  private suspend fun finish() {
    narrator.pause()
    restoreVolume()
    _state.value = State()
  }

  private fun restoreVolume() {
    if (!fading) return
    fading = false
    narrator.setVolume(1f)
  }

  private companion object {
    const val TICK_MS = 1_000L

    /** How long before the end the rate starts easing down. */
    const val WIND_DOWN_MS = 30_000L

    /** How quiet the fade gets before narration stops. */
    const val QUIETEST = 0.15f
  }
}
