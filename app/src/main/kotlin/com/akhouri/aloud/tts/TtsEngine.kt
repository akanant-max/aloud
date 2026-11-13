package com.akhouri.aloud.tts

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** An installed speech engine the user can pick. */
data class EngineInfo(val packageName: String, val label: String)

/** What the platform engine reports back about an utterance in flight. */
sealed interface TtsEvent {
  val utteranceId: String

  data class Started(override val utteranceId: String) : TtsEvent

  data class Done(override val utteranceId: String) : TtsEvent

  data class Failed(override val utteranceId: String, val errorCode: Int) : TtsEvent

  /** Word-level progress within the utterance, where the engine supplies it. */
  data class Range(
    override val utteranceId: String,
    val start: Int,
    val end: Int,
  ) : TtsEvent
}

/**
 * A coroutine-shaped wrapper over [TextToSpeech].
 *
 * The platform class is callback-driven, single-shot on initialisation, and throws if touched
 * after shutdown. Everything above this layer sees a flow of events and suspend functions.
 */
class TtsEngine(private val context: Context) {

  private val _events = MutableSharedFlow<TtsEvent>(extraBufferCapacity = 64)
  val events: SharedFlow<TtsEvent> = _events.asSharedFlow()

  private var tts: TextToSpeech? = null
  private var currentEnginePackage: String? = null

  @Volatile
  var isReady: Boolean = false
    private set

  /**
   * Starts the engine, or returns straight away if the requested one is already running.
   *
   * [preferredEngine] wins when it is installed; otherwise the system default is used, so the
   * user's own choice in Android settings still applies.
   */
  suspend fun ensureReady(preferredEngine: String? = null): Result<Unit> {
    val target = preferredEngine?.takeIf { pkg -> availableEngines().any { it.packageName == pkg } }

    if (isReady && tts != null && target == currentEnginePackage) return Result.success(Unit)

    shutdown()

    val status = withTimeoutOrNull(INIT_TIMEOUT_MS) {
      suspendCancellableCoroutine { continuation ->
        var engine: TextToSpeech? = null
        engine = TextToSpeech(
          context.applicationContext,
          { result -> if (continuation.isActive) continuation.resume(result) },
          target,
        )
        tts = engine
        continuation.invokeOnCancellation { runCatching { engine.shutdown() } }
      }
    }

    return when {
      status == null -> {
        shutdown()
        Result.failure(IllegalStateException("The speech engine did not start in time"))
      }
      status != TextToSpeech.SUCCESS -> {
        shutdown()
        Result.failure(IllegalStateException("The speech engine could not start"))
      }
      else -> {
        currentEnginePackage = target
        isReady = true
        configure()
        Result.success(Unit)
      }
    }
  }

  private fun configure() {
    val engine = tts ?: return
    // Speech belongs on the media stream, which is what routes it to Bluetooth headphones and
    // what the volume keys adjust during playback.
    engine.setAudioAttributes(
      AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build(),
    )
    engine.setOnUtteranceProgressListener(
      object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
          utteranceId?.let { _events.tryEmit(TtsEvent.Started(it)) }
        }

        override fun onDone(utteranceId: String?) {
          utteranceId?.let { _events.tryEmit(TtsEvent.Done(it)) }
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onError(utteranceId: String?) {
          utteranceId?.let { _events.tryEmit(TtsEvent.Failed(it, TextToSpeech.ERROR)) }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
          utteranceId?.let { _events.tryEmit(TtsEvent.Failed(it, errorCode)) }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
          // Deliberately quiet: a stop is something we asked for, not an event to react to.
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
          utteranceId?.let { _events.tryEmit(TtsEvent.Range(it, start, end)) }
        }
      },
    )
  }

  /**
   * Queues one utterance. [flush] clears anything pending first, which is what makes a seek or
   * a pause take effect immediately instead of after the current backlog.
   */
  fun speak(utteranceId: String, text: String, flush: Boolean = false) {
    val engine = tts ?: return
    if (text.isBlank()) {
      _events.tryEmit(TtsEvent.Done(utteranceId))
      return
    }
    val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
    val result = engine.speak(text, mode, Bundle(), utteranceId)
    if (result != TextToSpeech.SUCCESS) {
      _events.tryEmit(TtsEvent.Failed(utteranceId, result))
    }
  }

  /**
   * Renders text to an audio file and returns once the file is complete. False means the
   * engine refused or failed; the caller decides what that costs.
   */
  suspend fun synthesize(utteranceId: String, text: String, file: File): Boolean {
    val engine = tts ?: return false
    if (text.isBlank()) return false
    return withTimeoutOrNull(SYNTHESIS_TIMEOUT_MS) {
      coroutineScope {
        // Listening before asking, so a result that arrives at once is not missed.
        val outcome = async(start = CoroutineStart.UNDISPATCHED) {
          events.first {
            it.utteranceId == utteranceId && (it is TtsEvent.Done || it is TtsEvent.Failed)
          }
        }
        val queued = runCatching { engine.synthesizeToFile(text, Bundle(), file, utteranceId) }
          .getOrDefault(TextToSpeech.ERROR)
        if (queued != TextToSpeech.SUCCESS) {
          outcome.cancel()
          false
        } else {
          outcome.await() is TtsEvent.Done
        }
      }
    } ?: false
  }

  fun stop() {
    runCatching { tts?.stop() }
  }

  /** Engines cap input length; chunking has to respect whichever is lower. */
  fun maxInputLength(): Int = runCatching {
    TextToSpeech.getMaxSpeechInputLength()
  }.getOrDefault(4000)

  fun setSpeechRate(rate: Float) {
    runCatching { tts?.setSpeechRate(rate.coerceIn(MIN_RATE, MAX_RATE)) }
  }

  fun setPitch(pitch: Float) {
    runCatching { tts?.setPitch(pitch.coerceIn(MIN_PITCH, MAX_PITCH)) }
  }

  fun setVoice(voiceName: String?): Boolean {
    val engine = tts ?: return false
    if (voiceName == null) return false
    val voice = runCatching { engine.voices }.getOrNull()
      ?.firstOrNull { it.name == voiceName }
      ?: return false
    return runCatching { engine.setVoice(voice) == TextToSpeech.SUCCESS }.getOrDefault(false)
  }

  fun setLanguage(locale: Locale): Boolean {
    val engine = tts ?: return false
    val result = runCatching { engine.setLanguage(locale) }.getOrDefault(TextToSpeech.ERROR)
    return result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
  }

  /** Voices the running engine offers, best quality first. Empty if voice data is missing. */
  fun voices(): List<Voice> = runCatching {
    tts?.voices.orEmpty()
      .filterNot { it.isNetworkConnectionRequired }
      .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.name })
  }.getOrDefault(emptyList())

  fun currentVoice(): Voice? = runCatching { tts?.voice }.getOrNull()

  fun availableEngines(): List<EngineInfo> = runCatching {
    tts?.engines.orEmpty().map { EngineInfo(it.name, it.label) }
      .ifEmpty { probeEngines() }
  }.getOrElse { probeEngines() }

  /**
   * Engine discovery before the first [TextToSpeech] instance exists. Needs the TTS_SERVICE
   * entry in the manifest's `queries` block to see anything on API 30 and up.
   */
  private fun probeEngines(): List<EngineInfo> = runCatching {
    val intent = android.content.Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
    context.packageManager.queryIntentServices(intent, 0).map {
      EngineInfo(
        packageName = it.serviceInfo.packageName,
        label = it.serviceInfo.loadLabel(context.packageManager).toString(),
      )
    }
  }.getOrDefault(emptyList())

  fun shutdown() {
    isReady = false
    runCatching {
      tts?.stop()
      tts?.shutdown()
    }.onFailure { Log.d(TAG, "Shutdown was not clean", it) }
    tts = null
    currentEnginePackage = null
  }

  companion object {
    const val GOOGLE_TTS = "com.google.android.tts"

    const val MIN_RATE = 0.1f
    const val MAX_RATE = 4.0f
    const val MIN_PITCH = 0.5f
    const val MAX_PITCH = 2.0f

    private const val TAG = "TtsEngine"
    private const val INIT_TIMEOUT_MS = 10_000L
    private const val SYNTHESIS_TIMEOUT_MS = 90_000L
  }
}
