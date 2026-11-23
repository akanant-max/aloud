package com.akhouri.aloud

import com.akhouri.aloud.tts.Archives
import com.akhouri.aloud.tts.KokoroEngine
import com.akhouri.aloud.tts.NaturalVoices
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import javax.sound.sampled.AudioSystem
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Test

class NaturalVoicesTest {

  @Test
  fun writtenWavIsReadableAndCarriesTheSilence() {
    val dir = Files.createTempDirectory("wav").toFile()
    val file = File(dir, "a.wav")
    val samples = FloatArray(24_000) { i -> if (i % 2 == 0) 0.5f else -0.5f }
    KokoroEngine.writeWav(file, samples, 24_000, trailingSilence = 0.25f)
    AudioSystem.getAudioInputStream(file).use { input ->
      assertThat(input.format.sampleRate).isEqualTo(24_000f)
      assertThat(input.format.sampleSizeInBits).isEqualTo(16)
      assertThat(input.frameLength).isEqualTo(24_000L + 6_000L)
    }
    dir.deleteRecursively()
  }

  @Test
  fun untarKeepsWhatIsNeededAndSkipsTheRest() {
    val dir = Files.createTempDirectory("tar").toFile()
    val archive = File(dir, "pack.tar.bz2")
    TarArchiveOutputStream(BZip2CompressorOutputStream(archive.outputStream())).use { tar ->
      fun add(name: String, text: String) {
        val bytes = text.toByteArray()
        tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
        tar.write(bytes)
        tar.closeArchiveEntry()
      }
      add("${NaturalVoices.PACKAGE}/tokens.txt", "a 1")
      add("${NaturalVoices.PACKAGE}/espeak-ng-data/en_dict", "dict")
      add("${NaturalVoices.PACKAGE}/lexicon-zh.txt", "zh")
      add("${NaturalVoices.PACKAGE}/dict/jieba.dict.utf8", "jieba")
    }
    val out = File(dir, "out")
    var last = 0f
    Archives.untarBz2(archive, out, skip = { it.endsWith(".fst") || "lexicon-zh" in it || "/dict/" in "/$it" }) { last = it }
    val root = File(out, NaturalVoices.PACKAGE)
    assertThat(File(root, "tokens.txt").readText()).isEqualTo("a 1")
    assertThat(File(root, "espeak-ng-data/en_dict").isFile).isTrue()
    assertThat(File(root, "lexicon-zh.txt").exists()).isFalse()
    assertThat(File(root, "dict").exists()).isFalse()
    assertThat(last).isAtLeast(0f)
    dir.deleteRecursively()
  }

  @Test(expected = java.io.IOException::class)
  fun untarRefusesPathsOutsideTheFolder() {
    val dir = Files.createTempDirectory("evil").toFile()
    val archive = File(dir, "evil.tar.bz2")
    TarArchiveOutputStream(BZip2CompressorOutputStream(archive.outputStream())).use { tar ->
      val bytes = "x".toByteArray()
      tar.putArchiveEntry(TarArchiveEntry("ok/../../escape.txt", true).apply { size = 1 })
      tar.write(bytes)
      tar.closeArchiveEntry()
    }
    try {
      Archives.untarBz2(archive, File(dir, "out"), skip = { false }) { }
    } finally {
      dir.deleteRecursively()
    }
  }
}
