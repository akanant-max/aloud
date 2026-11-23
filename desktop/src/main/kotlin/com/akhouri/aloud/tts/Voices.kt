package com.akhouri.aloud.tts

import com.akhouri.aloud.AppPaths
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** A Piper voice on this PC: a model file with its settings file beside it. */
data class Voice(
  val id: String,
  val model: File,
  /** Shipped with the app, so it cannot be removed. */
  val bundled: Boolean,
) {
  val displayName: String get() = Voices.describe(id)
}

/** A voice that can be downloaded from Piper's voice collection. */
data class DownloadableVoice(
  val id: String,
  /** Path within the collection, without the file extension. */
  val path: String,
  val note: String,
)

object Voices {

  private const val COLLECTION = "https://huggingface.co/rhasspy/piper-voices/resolve/main/"

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

  /** Every usable voice: the bundled ones first, then those downloaded or added. */
  fun installed(): List<Voice> {
    fun scan(dir: File?, bundled: Boolean) = dir?.listFiles { f ->
      f.isFile && f.name.endsWith(".onnx") && File(f.path + ".json").isFile
    }.orEmpty().sortedBy { it.name }.map { Voice(it.name.removeSuffix(".onnx"), it, bundled) }

    val bundled = scan(AppPaths.bundledVoices, bundled = true)
    val user = scan(AppPaths.userVoices, bundled = false).filter { v -> bundled.none { it.id == v.id } }
    return bundled + user
  }

  /** The voice to use: the chosen one if it is still there, otherwise the first available. */
  fun resolve(chosenPath: String?): Voice? {
    val all = installed()
    return all.firstOrNull { it.model.absolutePath == chosenPath } ?: all.firstOrNull()
  }

  /** "en_US-lessac-medium" becomes "Lessac · English (US) · medium". */
  fun describe(id: String): String {
    val parts = id.split('-')
    if (parts.size < 3) return id
    val name = parts[1].split('_').joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
    val language = LANGUAGES[parts[0]] ?: parts[0]
    return "$name · $language · ${parts.drop(2).joinToString("-")}"
  }

  /** Downloads a catalogue voice into the user's voices folder, reporting progress from 0 to 1. */
  suspend fun download(voice: DownloadableVoice, onProgress: (Float) -> Unit): Result<Voice> =
    withContext(Dispatchers.IO) {
      runCatching {
        val dir = AppPaths.userVoices
        val model = File(dir, "${voice.id}.onnx")
        val config = File(dir, "${voice.id}.onnx.json")
        fetch(COLLECTION + voice.path + ".onnx.json", config) { }
        fetch(COLLECTION + voice.path + ".onnx", model, onProgress)
        Voice(voice.id, model, bundled = false)
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
    Voice(model.name.removeSuffix(".onnx"), File(dir, model.name), bundled = false)
  }

  fun remove(voice: Voice) {
    if (voice.bundled) return
    voice.model.delete()
    File(voice.model.path + ".json").delete()
  }

  private val client: HttpClient by lazy {
    HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.NORMAL)
      .connectTimeout(Duration.ofSeconds(20))
      .build()
  }

  /** Downloads to a temporary file and moves it into place only when complete. */
  private suspend fun fetch(url: String, target: File, onProgress: (Float) -> Unit) {
    val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
    if (response.statusCode() !in 200..299) error("The download failed (HTTP ${response.statusCode()})")
    val total = response.headers().firstValueAsLong("content-length").orElse(-1L)
    val partial = File(target.path + ".part")
    response.body().use { input ->
      partial.outputStream().use { output ->
        val buffer = ByteArray(256 * 1024)
        var done = 0L
        while (true) {
          coroutineContext.ensureActive()
          val read = input.read(buffer)
          if (read < 0) break
          output.write(buffer, 0, read)
          done += read
          if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
        }
      }
    }
    if (target.exists()) target.delete()
    if (!partial.renameTo(target)) error("The download could not be saved")
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
