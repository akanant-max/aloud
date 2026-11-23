package com.akhouri.aloud.tts

import com.akhouri.aloud.AppPaths
import com.akhouri.aloud.util.CrashReporter
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Kokoro, a natural-sounding neural voice model, run in-process through sherpa-onnx.
 *
 * The model is loaded once and kept, since loading takes a second or two; it is loaded again
 * only to switch between the American and British pronunciation dictionaries.
 */
class KokoroEngine {

  private val mutex = Mutex()
  private var tts: OfflineTts? = null
  private var loadedKey: String? = null
  private val counter = AtomicLong()

  @Volatile
  var lastError: String? = null
    private set

  /** The engine's native libraries ship with the app; the voices are a separate download. */
  val isSupported: Boolean get() = AppPaths.sherpaNative?.let { File(it, "sherpa-onnx-jni.dll").isFile } == true

  suspend fun synthesize(text: String, voice: Voice, rate: Float, outDir: File): File? = mutex.withLock {
    val line = text.replace(LINE_BREAKS, " ").trim()
    if (line.isEmpty()) return@withLock null
    withContext(Dispatchers.IO) {
      try {
        val engine = ensureLoaded(voice) ?: return@withContext null
        val audio = engine.generate(line, voice.speaker, rate.coerceIn(PiperEngine.MIN_RATE, PiperEngine.MAX_RATE))
        ensureActive()
        val samples = audio.samples
        if (samples.isEmpty()) {
          lastError = "The voice produced no sound"
          return@withContext null
        }
        outDir.mkdirs()
        val file = File(outDir, "k${System.nanoTime()}-${counter.incrementAndGet()}.wav")
        writeWav(file, samples, audio.sampleRate, trailingSilence = SENTENCE_SILENCE_S)
        file
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        CrashReporter.record(e, "running the natural voice")
        lastError = e.message ?: e.javaClass.simpleName
        // A failed model is dropped so the next attempt starts clean.
        releaseLocked()
        null
      }
    }
  }

  /** Lets the model go, freeing its memory; the next request loads it again. */
  suspend fun stop() = mutex.withLock { releaseLocked() }

  private fun ensureLoaded(voice: Voice): OfflineTts? {
    val dir = NaturalVoices.modelDir
    if (!NaturalVoices.isInstalled()) {
      lastError = "The natural voices are not installed"
      return null
    }
    val lexicon = File(dir, if (voice.british) "lexicon-gb-en.txt" else "lexicon-us-en.txt")
    val key = lexicon.absolutePath
    tts?.let { if (loadedKey == key) return it }
    releaseLocked()

    AppPaths.sherpaNative?.let { System.setProperty("sherpa_onnx.native.path", it.absolutePath) }
    val kokoro = OfflineTtsKokoroModelConfig.builder()
      .setModel(File(dir, NaturalVoices.MODEL_FILE).absolutePath)
      .setVoices(File(dir, "voices.bin").absolutePath)
      .setTokens(File(dir, "tokens.txt").absolutePath)
      .setLexicon(lexicon.absolutePath)
      .setDataDir(File(dir, "espeak-ng-data").absolutePath)
      .build()
    val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)
    val model = OfflineTtsModelConfig.builder()
      .setKokoro(kokoro)
      .setNumThreads(threads)
      .setDebug(false)
      .build()
    val config = OfflineTtsConfig.builder().setModel(model).setMaxNumSentences(1).build()
    return OfflineTts(config).also {
      tts = it
      loadedKey = key
    }
  }

  private fun releaseLocked() {
    runCatching { tts?.release() }
    tts = null
    loadedKey = null
  }

  companion object {
    /** The pause after each stretch, matching the Piper voices. */
    private const val SENTENCE_SILENCE_S = 0.25f
    private val LINE_BREAKS = Regex("[\\r\\n\\u2028\\u2029]+")

    /** Writes mono 16-bit PCM, which every Windows audio path plays. */
    fun writeWav(file: File, samples: FloatArray, sampleRate: Int, trailingSilence: Float = 0f) {
      val silence = (sampleRate * trailingSilence).toInt().coerceAtLeast(0)
      val count = samples.size + silence
      val dataBytes = count * 2
      DataOutputStream(file.outputStream().buffered(1 shl 16)).use { out ->
        fun intLe(v: Int) = out.writeInt(Integer.reverseBytes(v))
        fun shortLe(v: Int) = out.writeShort(java.lang.Short.reverseBytes(v.toShort()).toInt())
        out.writeBytes("RIFF"); intLe(36 + dataBytes); out.writeBytes("WAVE")
        out.writeBytes("fmt "); intLe(16); shortLe(1); shortLe(1)
        intLe(sampleRate); intLe(sampleRate * 2); shortLe(2); shortLe(16)
        out.writeBytes("data"); intLe(dataBytes)
        for (s in samples) shortLe((s.coerceIn(-1f, 1f) * 32767f).toInt())
        repeat(silence) { shortLe(0) }
      }
    }
  }
}
