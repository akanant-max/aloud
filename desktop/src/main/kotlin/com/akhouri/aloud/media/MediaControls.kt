package com.akhouri.aloud.media

import com.akhouri.aloud.AppPaths
import com.akhouri.aloud.tts.Narrator
import com.akhouri.aloud.util.CrashReporter
import java.io.BufferedWriter
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Puts Aloud into Windows' media controls — the flyout by the volume indicator, the keyboard's
 * media keys and Bluetooth headset buttons — through the small helper that ships with the app.
 * If the helper is missing or fails, Aloud carries on without it.
 */
class MediaControls(
  private val narrator: Narrator,
  private val skipParagraphs: () -> Int,
  private val scope: CoroutineScope,
) {

  private data class Shown(
    val title: String,
    val artist: String,
    val album: String,
    val cover: String,
    val state: String,
  )

  private var process: Process? = null
  private var input: BufferedWriter? = null
  private var wanted = false
  private var restarts = 0
  private var lastShown: Shown? = null

  fun start(enabled: Flow<Boolean>) {
    scope.launch {
      combine(enabled.distinctUntilChanged(), narrator.state.map { it.hasBook }.distinctUntilChanged()) { on, hasBook ->
        on && hasBook
      }.distinctUntilChanged().collect { on ->
        wanted = on
        if (on) launchHelper() else stopHelper()
      }
    }
    scope.launch {
      narrator.state
        .map { s ->
          Shown(
            title = s.chapterTitle ?: "Chapter ${s.chapterIndex + 1}",
            artist = s.author ?: s.bookTitle,
            album = s.bookTitle,
            cover = s.coverPath.orEmpty(),
            state = when {
              !s.hasBook -> "stopped"
              s.isPlaying -> "playing"
              else -> "paused"
            },
          )
        }
        .distinctUntilChanged()
        .collect { shown ->
          lastShown = shown
          send(shown)
        }
    }
  }

  fun shutdown() {
    wanted = false
    stopHelper()
  }

  private fun launchHelper() {
    if (process?.isAlive == true) return
    val exe = AppPaths.controlsExe?.takeIf { it.isFile } ?: return
    val started = runCatching {
      ProcessBuilder(exe.absolutePath).directory(exe.parentFile).redirectErrorStream(true).start()
    }.onFailure { CrashReporter.record(it, "starting the media controls helper") }.getOrNull() ?: return

    process = started
    input = started.outputStream.bufferedWriter(Charsets.UTF_8)
    lastShown?.let(::send)

    thread(isDaemon = true, name = "media-controls") {
      runCatching {
        started.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
          scope.launch { handle(line.trim()) }
        }
      }
      scope.launch { onHelperExited(started) }
    }
  }

  private suspend fun onHelperExited(exited: Process) {
    if (process != exited) return
    process = null
    input = null
    // It should live as long as the app; bring it back a few times if it falls over.
    if (wanted && restarts < MAX_RESTARTS) {
      restarts++
      delay(2_000)
      if (wanted) launchHelper()
    }
  }

  private fun stopHelper() {
    val running = process ?: return
    process = null
    runCatching {
      input?.apply {
        write("quit")
        newLine()
        flush()
      }
    }
    input = null
    runCatching { running.waitFor(1, java.util.concurrent.TimeUnit.SECONDS) }
    runCatching { running.destroy() }
  }

  private suspend fun handle(command: String) {
    when (command) {
      "play" -> narrator.play()
      "pause", "stop" -> narrator.pause()
      "toggle" -> narrator.togglePlayPause()
      "next" -> narrator.nextChapter()
      "previous" -> narrator.previousChapter()
      "forward" -> narrator.skipParagraphs(skipParagraphs())
      "rewind" -> narrator.skipParagraphs(-skipParagraphs())
      "ready" -> lastShown?.let(::send)
      else -> if (command.startsWith("error")) System.err.println("Media controls: $command")
    }
  }

  private fun send(shown: Shown) {
    val writer = input ?: return
    fun clean(text: String) = text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
    runCatching {
      writer.write("meta\t${clean(shown.title)}\t${clean(shown.artist)}\t${clean(shown.album)}\t${clean(shown.cover)}")
      writer.newLine()
      writer.write("state\t${shown.state}")
      writer.newLine()
      writer.flush()
    }
  }

  private companion object {
    const val MAX_RESTARTS = 5
  }
}
