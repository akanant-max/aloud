package com.akhouri.aloud.tts

import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

internal object Archives {

  /**
   * Unpacks a .tar.bz2 into [into], leaving out entries [skip] matches and refusing any entry
   * that would land outside [into]. Progress, from 0 to 1, follows the compressed bytes read.
   */
  fun untarBz2(archive: File, into: File, skip: (String) -> Boolean, onProgress: (Float) -> Unit) {
    into.mkdirs()
    val root = into.canonicalFile
    val total = archive.length().coerceAtLeast(1L)
    val counted = CountingInputStream(archive.inputStream().buffered(1 shl 20))
    TarArchiveInputStream(BZip2CompressorInputStream(counted)).use { tar ->
      var lastReported = -1
      while (true) {
        val entry = tar.nextEntry ?: break
        val name = entry.name.replace('\\', '/').trimStart('/')
        if (name.isEmpty() || skip(name)) continue
        val target = File(root, name).canonicalFile
        if (!target.path.startsWith(root.path + File.separator) && target != root) {
          throw IOException("The archive holds an unsafe path: ${entry.name}")
        }
        when {
          entry.isDirectory -> target.mkdirs()
          entry.isFile -> {
            target.parentFile?.mkdirs()
            target.outputStream().buffered(1 shl 16).use { out -> tar.copyTo(out, 1 shl 16) }
          }
          // Links and devices are not needed by the voices.
        }
        val percent = (counted.count * 100 / total).toInt()
        if (percent != lastReported) {
          lastReported = percent
          onProgress((percent / 100f).coerceIn(0f, 1f))
        }
      }
    }
  }

  private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    @Volatile
    var count = 0L
      private set

    override fun read(): Int = super.read().also { if (it >= 0) count++ }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
      super.read(b, off, len).also { if (it > 0) count += it }

    override fun skip(n: Long): Long = super.skip(n).also { count += it }
  }
}
