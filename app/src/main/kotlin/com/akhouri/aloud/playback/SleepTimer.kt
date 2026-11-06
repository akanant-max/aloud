package com.akhouri.aloud.playback

import com.akhouri.aloud.tts.Narrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Stops narration after a set time, or at the end of the chapter.
 *
 * A fade-out makes no sense for speech — it would just make the last sentence unintelligible —
 * so instead the rate eases down over the final stretch, which is a clear signal that it is
 * about to stop without losing any words.
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
  private var rateBeforeWindDown: Float? = null

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
      narrator.state.collect { narration ->
        val chapterChanged = narration.chapterIndex != startingChapter
        if (chapterChanged || (!narration.isPlaying && !narration.isPreparing)) {
          finish()
          return@collect
        }
      }
    }
  }

  fun cancel() {
    job?.cancel()
    job = null
    restoreRate()
    _state.value = State()
  }

  /** Adds time without disturbing a timer already counting down. */
  fun extend(byMs: Long) {
    val current = _state.value
    if (!current.running || current.untilEndOfChapter) return
    start(current.remainingMs + byMs)
  }

  private suspend fun windDown(remainingMs: Long) {
    val narration = narrator.state.value
    if (rateBeforeWindDown == null) rateBeforeWindDown = narration.speechRate
    val original = rateBeforeWindDown ?: return
    val fraction = (remainingMs.toFloat() / WIND_DOWN_MS).coerceIn(0f, 1f)
    val target = (original * (SLOWEST_FRACTION + (1f - SLOWEST_FRACTION) * fraction))
    narrator.setSpeechRate(target)
  }

  private suspend fun finish() {
    narrator.pause()
    restoreRate()
    _state.value = State()
  }

  private fun restoreRate() {
    val original = rateBeforeWindDown ?: return
    rateBeforeWindDown = null
    scope.launch { narrator.setSpeechRate(original) }
  }

  private companion object {
    const val TICK_MS = 1_000L

    /** How long before the end the rate starts easing down. */
    const val WIND_DOWN_MS = 30_000L

    /** The slowest the wind-down goes, as a fraction of the chosen rate. */
    const val SLOWEST_FRACTION = 0.7f
  }
}
