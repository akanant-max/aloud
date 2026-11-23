package com.akhouri.aloud.tts

import java.io.File

/** Sends each request to the engine the voice belongs to. */
class Speech(
  val piper: PiperEngine = PiperEngine(),
  val natural: KokoroEngine = KokoroEngine(),
) {
  @Volatile
  private var lastEngine: VoiceEngine = VoiceEngine.Piper

  /** Null when the voice can be used; otherwise why not, in words for the listener. */
  fun problemWith(voice: Voice): String? = when (voice.engine) {
    VoiceEngine.Piper ->
      if (piper.isInstalled) null
      else "The speech engine is missing from this installation. Reinstalling Aloud should fix it."
    VoiceEngine.Natural -> when {
      !natural.isSupported -> "The natural voices need the newest Aloud. Reinstalling it should fix this."
      !NaturalVoices.isInstalled() -> "The natural voices are not downloaded. Get them in Settings, under Voice."
      else -> null
    }
  }

  suspend fun synthesize(text: String, voice: Voice, rate: Float, outDir: File): File? {
    lastEngine = voice.engine
    return when (voice.engine) {
      VoiceEngine.Piper -> piper.synthesize(text, voice.model, rate, outDir)
      VoiceEngine.Natural -> natural.synthesize(text, voice, rate, outDir)
    }
  }

  /** What went wrong most recently with the engine last used. */
  val lastError: String?
    get() = when (lastEngine) {
      VoiceEngine.Piper -> piper.lastError
      VoiceEngine.Natural -> natural.lastError
    }

  /** Stops the engines not needed for [voice], freeing their memory. */
  suspend fun keepOnly(voice: Voice?) {
    if (voice?.engine != VoiceEngine.Piper) piper.stop()
    if (voice?.engine != VoiceEngine.Natural) natural.stop()
  }

  suspend fun stop() {
    piper.stop()
    natural.stop()
  }
}
