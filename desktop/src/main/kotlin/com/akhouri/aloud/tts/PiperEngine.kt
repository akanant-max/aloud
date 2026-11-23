package com.akhouri.aloud.tts

import com.akhouri.aloud.AppPaths
import com.akhouri.aloud.util.CrashReporter
import java.io.BufferedWriter
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Piper, an offline neural speech engine, run as a long-lived helper process.
 *
 * Piper takes one line of text at a time on its input, writes it as a WAV file into a folder,
 * and prints the file's path. Keeping one process alive avoids reloading the voice model for
 * every sentence; it is restarted only when the voice or the speed changes, since both are
 * fixed when Piper starts.
 */
class PiperEngine {

  private class Session(
    val process: Process,
    val input: BufferedWriter,
    val output: Channel<String>,
    val key: String,
  )

  private val mutex = Mutex()
  private var session: Session? = null

  /** The last lines Piper wrote to its error stream, for reporting a failure usefully. */
  private val recentErrors = ArrayDeque<String>()

  val isInstalled: Boolean get() = AppPaths.piperExe?.isFile == true

  /** What went wrong most recently, in Piper's own words. */
  val lastError: String? get() = synchronized(recentErrors) { recentErrors.lastOrNull() }

  /**
   * Renders [text] with [voice] at [rate] times normal speed into a WAV file in [outDir].
   * Returns null if Piper could not produce it. Cancelling this abandons the Piper process,
   * so nothing it was still writing can be mistaken for the next request's file.
   */
  suspend fun synthesize(text: String, voice: File, rate: Float, outDir: File): File? = mutex.withLock {
    val line = text.replace(LINE_BREAKS, " ").trim()
    if (line.isEmpty()) return@withLock null

    withContext(Dispatchers.IO) {
      try {
        val current = ensureSession(voice, rate, outDir) ?: return@withContext null
        val before = outDir.list()?.toHashSet() ?: hashSetOf()
        current.input.write(line)
        current.input.newLine()
        current.input.flush()

        val result = awaitFile(current, outDir, before)
        if (result == null) stopLocked()
        result
      } catch (e: CancellationException) {
        stopLocked()
        throw e
      } catch (e: Exception) {
        CrashReporter.record(e, "running Piper")
        stopLocked()
        null
      }
    }
  }

  /**
   * Waits for the file Piper reports on its output. As a fallback, a new file appearing in the
   * folder and no longer growing is taken as the answer too, so a build of Piper that writes
   * without reporting still works.
   */
  private suspend fun awaitFile(current: Session, outDir: File, before: Set<String>): File? {
    val deadline = System.currentTimeMillis() + RENDER_TIMEOUT_MS
    var candidate: File? = null
    var candidateSize = -1L
    while (System.currentTimeMillis() < deadline) {
      val printed = withTimeoutOrNull(POLL_MS) { current.output.receiveCatching() }
      if (printed != null) {
        val line = printed.getOrNull() ?: return null // Piper exited
        if (line.endsWith(".wav", ignoreCase = true)) {
          File(line).takeIf { it.isFile }?.let { return it }
        }
        continue
      }
      if (!current.process.isAlive) return null

      val fresh = outDir.listFiles { f -> f.isFile && f.name.endsWith(".wav") && f.name !in before }
        ?.maxByOrNull { it.lastModified() }
      if (fresh != null) {
        val size = fresh.length()
        if (fresh == candidate && size == candidateSize && size > WAV_HEADER_BYTES) return fresh
        candidate = fresh
        candidateSize = size
      }
    }
    return null
  }

  /** Ends the Piper process; the next request starts a fresh one. */
  suspend fun stop() = mutex.withLock { stopLocked() }

  private fun ensureSession(voice: File, rate: Float, outDir: File): Session? {
    val lengthScale = (1f / rate.coerceIn(MIN_RATE, MAX_RATE))
    val key = "${voice.absolutePath}|${"%.3f".format(Locale.US, lengthScale)}|${outDir.absolutePath}"
    session?.let { existing ->
      if (existing.key == key && existing.process.isAlive) return existing
      stopLocked()
    }

    val exe = AppPaths.piperExe?.takeIf { it.isFile } ?: return null
    outDir.mkdirs()
    val process = ProcessBuilder(
      exe.absolutePath,
      "--model", voice.absolutePath,
      "--output_dir", outDir.absolutePath,
      "--length_scale", "%.3f".format(Locale.US, lengthScale),
      "--sentence_silence", "0.25",
    )
      .directory(exe.parentFile)
      .start()

    val output = Channel<String>(Channel.UNLIMITED)
    thread(isDaemon = true, name = "piper-out") {
      runCatching {
        process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { output.trySend(it.trim()) }
      }
      output.close()
    }
    thread(isDaemon = true, name = "piper-err") {
      runCatching {
        process.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
          synchronized(recentErrors) {
            recentErrors.addLast(line)
            while (recentErrors.size > 20) recentErrors.removeFirst()
          }
        }
      }
    }

    val input = process.outputStream.bufferedWriter(Charsets.UTF_8)
    return Session(process, input, output, key).also { session = it }
  }

  private fun stopLocked() {
    val current = session ?: return
    session = null
    runCatching { current.input.close() }
    runCatching { current.process.destroy() }
    current.output.close()
  }

  companion object {
    const val MIN_RATE = 0.5f
    const val MAX_RATE = 3.0f
    private const val RENDER_TIMEOUT_MS = 120_000L
    private const val POLL_MS = 400L
    private const val WAV_HEADER_BYTES = 64L
    private val LINE_BREAKS = Regex("[\\r\\n\\u2028\\u2029]+")
  }
}
