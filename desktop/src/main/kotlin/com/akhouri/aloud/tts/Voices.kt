package com.akhouri.aloud.tts

import com.akhouri.aloud.AppPaths
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

enum class VoiceEngine { Piper, Natural }

/**
 * A voice on this PC. A Piper voice is a model file with its settings file beside it; a
 * natural voice is one speaker of the shared Kokoro model.
 */
data class Voice(
  val id: String,
  val model: File,
  /** Shipped with the app, so it cannot be removed. */
  val bundled: Boolean,
  val engine: VoiceEngine = VoiceEngine.Piper,
  val speaker: Int = 0,
  val label: String? = null,
  val note: String? = null,
  val british: Boolean = false,
) {
  /** What settings remember the voice by. A Piper voice keeps its old form, the model's path. */
  val key: String get() = if (engine == VoiceEngine.Natural) "natural:$id" else model.absolutePath

  val displayName: String get() = label ?: Voices.describe(id)
}

/** A voice that can be downloaded from Piper's voice collection. */
data class DownloadableVoice(
  val id: String,
  /** Path within the collection, without the file extension. */
  val path: String,
  val note: String,
)

/** One speaker of the natural (Kokoro) model. */
data class NaturalSpeaker(val id: String, val sid: Int, val name: String, val note: String)

/**
 * The natural voices: Kokoro, the open model that comes closest to the voices phones use. It is
 * one download shared by every speaker, kept out of the installer for its size.
 */
object NaturalVoices {
  const val PACKAGE = "kokoro-int8-multi-lang-v1_0"
  const val MODEL_FILE = "model.int8.onnx"
  const val DOWNLOAD_MB = 126
  private const val URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$PACKAGE.tar.bz2"

  val modelDir: File get() = File(AppPaths.naturalVoices, PACKAGE)

  /** Speaker numbers are those of the v1.0 model. Heart is first: the closest to Google's voice. */
  val speakers = listOf(
    NaturalSpeaker("af_heart", 3, "Heart", "American, female · closest to Google's voice"),
    NaturalSpeaker("af_bella", 2, "Bella", "American, female · warm"),
    NaturalSpeaker("af_nicole", 6, "Nicole", "American, female · soft, close"),
    NaturalSpeaker("af_sarah", 9, "Sarah", "American, female · bright"),
    NaturalSpeaker("af_aoede", 1, "Aoede", "American, female · smooth"),
    NaturalSpeaker("af_kore", 5, "Kore", "American, female · even"),
    NaturalSpeaker("am_michael", 16, "Michael", "American, male · steady"),
    NaturalSpeaker("am_fenrir", 14, "Fenrir", "American, male · deep"),
    NaturalSpeaker("am_puck", 18, "Puck", "American, male · lively"),
    NaturalSpeaker("am_adam", 11, "Adam", "American, male"),
    NaturalSpeaker("bf_emma", 21, "Emma", "British, female"),
    NaturalSpeaker("bf_isabella", 22, "Isabella", "British, female · soft"),
    NaturalSpeaker("bm_george", 26, "George", "British, male"),
    NaturalSpeaker("bm_fable", 25, "Fable", "British, male · storyteller"),
  )

  fun isInstalled(): Boolean = listOf(MODEL_FILE, "voices.bin", "tokens.txt", "lexicon-us-en.txt")
    .all { File(modelDir, it).isFile } && File(modelDir, "espeak-ng-data").isDirectory

  fun installed(): List<Voice> {
    if (!isInstalled()) return emptyList()
    return speakers.map { s ->
      Voice(
        id = s.id,
        model = modelDir,
        bundled = false,
        engine = VoiceEngine.Natural,
        speaker = s.sid,
        label = "${s.name} · natural",
        note = s.note,
        british = s.id.startsWith("b"),
      )
    }
  }

  /** Downloads and unpacks the model, reporting progress from 0 to 1. */
  suspend fun download(onProgress: (Float) -> Unit): Result<Unit> = Downloads.serial {
    withContext(Dispatchers.IO) {
      runCatching {
        val root = AppPaths.naturalVoices
        val archive = File(root, "$PACKAGE.tar.bz2")
        Downloads.fetchWithRetry(URL, archive) { onProgress(it * 0.8f) }
        try {
          val staging = File(root, "$PACKAGE.unpacking")
          staging.deleteRecursively()
          Archives.untarBz2(archive, staging, skip = { name ->
            name.endsWith(".fst") || "lexicon-zh" in name || "/dict/" in "/$name"
          }) { onProgress(0.8f + it * 0.2f) }
          // The archive holds one folder named after the package.
          val unpacked = File(staging, PACKAGE).takeIf { it.isDirectory } ?: staging
          modelDir.deleteRecursively()
          if (!unpacked.renameTo(modelDir)) throw IOException("The voices could not be put in place")
          staging.deleteRecursively()
        } finally {
          archive.delete()
        }
        if (!isInstalled()) throw IOException("The download was incomplete")
        onProgress(1f)
      }
    }
  }

  fun remove() {
    modelDir.deleteRecursively()
  }
}

object Voices {

  private const val COLLECTION = "https://huggingface.co/rhasspy/piper-voices/resolve/main/"
  private const val MIRROR = "https://github.com/akanant-max/aloud/releases/download/voices/"

  /** A short, tested list of English voices worth having. More can be added from a file. */
  val catalogue = listOf(
    DownloadableVoice("en_US-lessac-medium", "en/en_US/lessac/medium/en_US-lessac-medium", "Clear, even; the default"),
    DownloadableVoice("en_US-lessac-high", "en/en_US/lessac/high/en_US-lessac-high", "The same voice at higher quality; larger and slower"),
    DownloadableVoice("en_US-ryan-high", "en/en_US/ryan/high/en_US-ryan-high", "Warm, male"),
    DownloadableVoice("en_US-amy-medium", "en/en_US/amy/medium/en_US-amy-medium", "Bright, female"),
    DownloadableVoice("en_US-hfc_female-medium", "en/en_US/hfc_female/medium/en_US-hfc_female-medium", "Calm, female"),
    DownloadableVoice("en_GB-alba-medium", "en/en_GB/alba/medium/en_GB-alba-medium", "Scottish English, female"),
    DownloadableVoice("en_GB-cori-high", "en/en_GB/cori/high/en_GB-cori-high", "British English, female"),
    DownloadableVoice("en_GB-northern_english_male-medium", "en/en_GB/northern_english_male/medium/en_GB-northern_english_male-medium", "Northern English, male"),
  )

  private val fixedOnce = java.util.Collections.synchronizedSet(HashSet<String>())

  /** Piper voices on this PC: the bundled ones first, then those downloaded or added. */
  fun installedPiper(): List<Voice> {
    fun scan(dir: File?, bundled: Boolean) = dir?.listFiles { f ->
      f.isFile && f.name.endsWith(".onnx") && File(f.path + ".json").isFile
    }.orEmpty().sortedBy { it.name }.map { Voice(it.name.removeSuffix(".onnx"), it, bundled) }

    val bundled = scan(AppPaths.bundledVoices, bundled = true)
    val user = scan(AppPaths.userVoices, bundled = false).filter { v -> bundled.none { it.id == v.id } }
    // Voices downloaded before the settings-file fix are mended once, quietly.
    user.forEach { v -> if (fixedOnce.add(v.model.path)) runCatching { VoiceConfigFix.apply(File(v.model.path + ".json")) } }
    return bundled + user
  }

  /** Every usable voice. */
  fun installed(): List<Voice> = installedPiper() + NaturalVoices.installed()

  /** The voice to use: the chosen one if it is still there, otherwise the first available. */
  fun resolve(chosenKey: String?): Voice? {
    val all = installed()
    return all.firstOrNull { it.key == chosenKey } ?: all.firstOrNull()
  }

  /** "en_US-lessac-medium" becomes "Lessac · English (US) · medium". */
  fun describe(id: String): String {
    val parts = id.split('-')
    if (parts.size < 3) return id
    val name = parts[1].split('_').joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
    val language = LANGUAGES[parts[0]] ?: parts[0]
    return "$name · $language · ${parts.drop(2).joinToString("-")}"
  }

  /**
   * Downloads a catalogue voice into the user's voices folder, reporting progress from 0 to 1.
   * Downloads run one at a time, however many are asked for, and each retries on its own.
   */
  suspend fun download(voice: DownloadableVoice, onProgress: (Float) -> Unit): Result<Voice> = Downloads.serial {
    withContext(Dispatchers.IO) {
      runCatching {
        val dir = AppPaths.userVoices
        val model = File(dir, "${voice.id}.onnx")
        val config = File(dir, "${voice.id}.onnx.json")
        // Aloud's own copy, checked to work on Windows, first; the original collection if not.
        val sources = listOf(MIRROR + voice.id, COLLECTION + voice.path)
        var failure: Throwable? = null
        for (base in sources) {
          try {
            Downloads.fetchWithRetry("$base.onnx.json", config) { }
            Downloads.fetchWithRetry("$base.onnx", model, onProgress)
            VoiceConfigFix.apply(config)
            return@runCatching Voice(voice.id, model, bundled = false)
          } catch (e: CancellationException) {
            config.delete()
            throw e
          } catch (e: Throwable) {
            config.delete()
            failure = e
          }
        }
        throw failure ?: IOException("The voice could not be downloaded")
      }
    }
  }

  /** Adds a voice model chosen from disk; its settings file must sit beside it. */
  fun addFromFile(model: File): Result<Voice> = runCatching {
    val config = File(model.path + ".json")
    require(model.name.endsWith(".onnx")) { "Choose a Piper voice model, a file ending in .onnx" }
    require(config.isFile) { "The voice's settings file, ${config.name}, must be in the same folder" }
    val dir = AppPaths.userVoices
    model.copyTo(File(dir, model.name), overwrite = true)
    config.copyTo(File(dir, config.name), overwrite = true)
    VoiceConfigFix.apply(File(dir, config.name))
    Voice(model.name.removeSuffix(".onnx"), File(dir, model.name), bundled = false)
  }

  fun remove(voice: Voice) {
    if (voice.bundled || voice.engine != VoiceEngine.Piper) return
    voice.model.delete()
    File(voice.model.path + ".json").delete()
  }

  private val LANGUAGES = mapOf(
    "en_US" to "English (US)",
    "en_GB" to "English (UK)",
    "de_DE" to "German",
    "fr_FR" to "French",
    "es_ES" to "Spanish",
    "es_MX" to "Spanish (Mexico)",
    "it_IT" to "Italian",
    "hi_IN" to "Hindi",
    "ne_NP" to "Nepali",
    "pt_BR" to "Portuguese (Brazil)",
    "ru_RU" to "Russian",
    "zh_CN" to "Chinese",
  )
}

/** Shared download plumbing: one at a time, resumable, retried. */
internal object Downloads {

  private val queue = Mutex()

  /** Runs [block] once every download asked for earlier has finished. */
  suspend fun <T> serial(block: suspend () -> T): T = queue.withLock { block() }

  private val client: HttpClient by lazy {
    HttpClient.newBuilder()
      // HTTP/1.1: long transfers over HTTP/2 to these hosts were being cut off.
      .version(HttpClient.Version.HTTP_1_1)
      .followRedirects(HttpClient.Redirect.ALWAYS)
      .connectTimeout(Duration.ofSeconds(30))
      .build()
  }

  /**
   * Downloads [url] to [target], trying up to four times and carrying on from where a broken
   * transfer stopped. The file appears under its own name only when it is complete.
   */
  suspend fun fetchWithRetry(url: String, target: File, onProgress: (Float) -> Unit) {
    var lastError: Throwable? = null
    for (attempt in 0 until ATTEMPTS) {
      coroutineContext.ensureActive()
      if (attempt > 0) delay(BACKOFF_MS * attempt * attempt)
      try {
        fetch(url, target, onProgress)
        return
      } catch (e: CancellationException) {
        throw e
      } catch (e: HttpStatusException) {
        lastError = e
        // A missing file will not appear by asking again.
        if (e.status == 404 || e.status == 401 || e.status == 403) break
      } catch (e: Exception) {
        lastError = e
      }
    }
    File(target.path + ".part").delete()
    val reason = when (val e = lastError) {
      is HttpStatusException -> "the server answered ${e.status}"
      null -> "unknown"
      else -> e.message ?: e.javaClass.simpleName
    }
    throw IOException("${target.name} could not be downloaded after $ATTEMPTS tries ($reason)")
  }

  private class HttpStatusException(val status: Int) : IOException("HTTP $status")

  private suspend fun fetch(url: String, target: File, onProgress: (Float) -> Unit) {
    val partial = File(target.path + ".part")
    target.parentFile?.mkdirs()
    val have = if (partial.isFile) partial.length() else 0L
    val builder = HttpRequest.newBuilder(URI.create(url))
      .timeout(Duration.ofMinutes(30))
      .header("User-Agent", "Aloud/${AppPaths.version} (Windows)")
      .GET()
    if (have > 0) builder.header("Range", "bytes=$have-")
    val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
    val status = response.statusCode()
    if (status !in 200..299) {
      response.body().close()
      if (status == 416) partial.delete()
      throw HttpStatusException(status)
    }
    val resumed = status == 206 && have > 0
    val length = response.headers().firstValueAsLong("content-length").orElse(-1L)
    val total = if (length > 0) (if (resumed) have + length else length) else -1L
    var done = if (resumed) have else 0L
    response.body().use { input ->
      RandomAccessFile(partial, "rw").use { out ->
        if (resumed) out.seek(have) else out.setLength(0)
        val buffer = ByteArray(256 * 1024)
        while (true) {
          coroutineContext.ensureActive()
          val read = input.read(buffer)
          if (read < 0) break
          out.write(buffer, 0, read)
          done += read
          if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
        }
      }
    }
    if (total > 0 && done < total) throw IOException("The connection closed early")
    if (target.exists()) target.delete()
    if (!partial.renameTo(target)) throw IOException("The download could not be saved")
  }

  private const val ATTEMPTS = 4
  private const val BACKOFF_MS = 2_000L
}
