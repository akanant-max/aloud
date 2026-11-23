package com.akhouri.aloud.audio

import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import kotlin.math.log10

/**
 * Plays WAV files one after another through a single audio line, so consecutive files join
 * without a gap. Pausing stops the line where it is, so resuming carries on from the same word.
 *
 * Everything that writes audio is meant to run on one thread; [pause], [resume] and [volume]
 * may be called from any.
 */
class WavPlayer {

  @Volatile private var paused = false
  @Volatile private var line: SourceDataLine? = null
  private var lineFormat: AudioFormat? = null

  /** 0 to 1. */
  @Volatile
  var volume: Float = 1f
    set(value) {
      field = value.coerceIn(0f, 1f)
      applyVolume(line)
    }

  val isPaused: Boolean get() = paused

  /**
   * Plays one file to the end, blocking. Returns false if [cancelled] turned true first or the
   * file could not be played. While paused it waits, holding its place.
   */
  fun play(file: File, cancelled: () -> Boolean): Boolean {
    val input = runCatching { AudioSystem.getAudioInputStream(file) }.getOrNull() ?: return false
    input.use { stream ->
      val format = stream.format
      val out = lineFor(format) ?: return false
      val frame = format.frameSize.coerceAtLeast(1)
      val buffer = ByteArray(frame * 2048)

      while (true) {
        if (cancelled()) return false
        val read = stream.read(buffer, 0, buffer.size)
        if (read <= 0) break
        var offset = 0
        while (offset < read) {
          if (cancelled()) return false
          if (paused) {
            Thread.sleep(20)
            continue
          }
          if (!out.isRunning) out.start()
          // Writing only what fits never blocks, so pause and cancel take effect at once.
          val room = out.available() - out.available() % frame
          if (room <= 0) {
            Thread.sleep(5)
            continue
          }
          val count = minOf(read - offset, room)
          val written = runCatching { out.write(buffer, offset, count) }.getOrElse {
            // The audio device went away; reopen on the next write.
            closeLine()
            return false
          }
          offset += written
        }
      }
    }
    return true
  }

  /** Waits until everything written so far has been heard, or until [cancelled]. */
  fun drain(cancelled: () -> Boolean) {
    val out = line ?: return
    while (!cancelled() && out.isOpen && out.available() < out.bufferSize) {
      Thread.sleep(15)
    }
  }

  fun pause() {
    paused = true
    line?.stop()
  }

  fun resume() {
    paused = false
    line?.start()
  }

  /** Throws away whatever is waiting to be heard, for a jump to somewhere else. */
  fun discard() {
    line?.let {
      it.stop()
      it.flush()
    }
  }

  fun close() {
    closeLine()
  }

  private fun lineFor(format: AudioFormat): SourceDataLine? {
    val existing = line
    if (existing != null && existing.isOpen && lineFormat?.matches(format) == true) return existing
    closeLine()
    return runCatching {
      val opened = AudioSystem.getSourceDataLine(format)
      // Half a second of buffer: enough to ride over a busy moment, short enough to react.
      val bytes = (format.frameRate * format.frameSize / 2).toInt().coerceAtLeast(format.frameSize * 1024)
      opened.open(format, bytes)
      applyVolume(opened)
      if (!paused) opened.start()
      line = opened
      lineFormat = format
      opened
    }.getOrNull()
  }

  private fun closeLine() {
    val old = line ?: return
    line = null
    lineFormat = null
    runCatching {
      old.stop()
      old.flush()
      old.close()
    }
  }

  private fun applyVolume(target: SourceDataLine?) {
    val out = target ?: return
    runCatching {
      if (out.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
        val gain = out.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
        val db = if (volume <= 0.0001f) gain.minimum else (20f * log10(volume))
        gain.value = db.coerceIn(gain.minimum, gain.maximum)
      }
    }
  }
}
