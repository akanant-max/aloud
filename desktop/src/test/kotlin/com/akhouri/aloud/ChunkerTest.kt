package com.akhouri.aloud

import com.akhouri.aloud.tts.Chunker
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChunkerTest {

  @Test
  fun `short paragraph becomes one chunk covering the whole text`() {
    val text = "A short paragraph."
    val chunks = Chunker.chunk(paragraphIndex = 3, text = text)

    assertThat(chunks).hasSize(1)
    assertThat(chunks[0].paragraphIndex).isEqualTo(3)
    assertThat(chunks[0].startOffset).isEqualTo(0)
    assertThat(chunks[0].endOffset).isEqualTo(text.length)
    assertThat(chunks[0].text).isEqualTo(text)
  }

  @Test
  fun `every chunk stays within the limit`() {
    val text = (1..60).joinToString(" ") { "This is sentence number $it in a long paragraph." }
    val chunks = Chunker.chunk(0, text, maxChars = 200)

    assertThat(chunks).isNotEmpty()
    chunks.forEach { assertThat(it.text.length).isAtMost(200) }
  }

  @Test
  fun `offsets are contiguous and cover the paragraph`() {
    val text = (1..40).joinToString(" ") { "Sentence $it here." }
    val chunks = Chunker.chunk(0, text, maxChars = 120)

    assertThat(chunks.first().startOffset).isEqualTo(0)
    assertThat(chunks.last().endOffset).isEqualTo(text.length)
    chunks.zipWithNext { a, b ->
      assertThat(b.startOffset).isEqualTo(a.endOffset)
    }
  }

  @Test
  fun `no chunk boundary falls inside a word`() {
    val text = (1..50).joinToString(" ") { "antidisestablishmentarianism$it" }
    val chunks = Chunker.chunk(0, text, maxChars = 100)

    assertThat(chunks).isNotEmpty()
    chunks.forEach { chunk ->
      // A boundary only splits a word when the characters either side of it both belong to
      // one. Landing on the space that follows a word is a clean break, not a split.
      assertThat(splitsAWord(text, chunk.startOffset)).isFalse()
      assertThat(splitsAWord(text, chunk.endOffset)).isFalse()
    }
  }

  private fun splitsAWord(text: String, offset: Int): Boolean =
    offset in 1 until text.length &&
      text[offset - 1].isLetterOrDigit() &&
      text[offset].isLetterOrDigit()

  @Test
  fun `a single sentence longer than the limit is split on words`() {
    val text = "word ".repeat(300).trim()
    val chunks = Chunker.chunk(0, text, maxChars = 150)

    assertThat(chunks.size).isGreaterThan(1)
    chunks.forEach {
      assertThat(it.text.length).isAtMost(150)
      assertThat(it.text).doesNotContain("wor ")
    }
  }

  @Test
  fun `resuming from an offset skips what was already spoken`() {
    val text = "First sentence. Second sentence. Third sentence."
    val resumeAt = text.indexOf("Second")
    val chunks = Chunker.chunk(0, text, fromOffset = resumeAt)

    assertThat(chunks).isNotEmpty()
    assertThat(chunks.first().startOffset).isEqualTo(resumeAt)
    assertThat(chunks.joinToString(" ") { it.text }).doesNotContain("First")
  }

  @Test
  fun `an offset past the end produces nothing rather than throwing`() {
    assertThat(Chunker.chunk(0, "Short.", fromOffset = 999)).isEmpty()
  }

  @Test
  fun `blank and whitespace paragraphs produce no chunks`() {
    assertThat(Chunker.chunk(0, "")).isEmpty()
    assertThat(Chunker.chunk(0, "   \n\t  ")).isEmpty()
  }

  @Test
  fun `unicode text is chunked without splitting characters`() {
    val text = (1..40).joinToString(" ") { "এটি একটি বাক্য $it।" }
    val chunks = Chunker.chunk(0, text, maxChars = 120)

    assertThat(chunks).isNotEmpty()
    assertThat(chunks.joinToString("") { text.substring(it.startOffset, it.endOffset) })
      .isEqualTo(text)
  }

  @Test
  fun `reassembling the chunks reproduces the paragraph`() {
    val text = (1..30).joinToString(" ") { "Sentence $it with a few words in it." }
    val chunks = Chunker.chunk(0, text, maxChars = 90)

    val rebuilt = chunks.joinToString("") { text.substring(it.startOffset, it.endOffset) }
    assertThat(rebuilt).isEqualTo(text)
  }

  @Test
  fun `chunkAll keeps paragraphs in document order`() {
    val paragraphs = listOf(5 to "Paragraph five.", 6 to "Paragraph six.", 7 to "Paragraph seven.")
    val chunks = Chunker.chunkAll(paragraphs)

    assertThat(chunks.map { it.paragraphIndex }).isInOrder()
    assertThat(chunks.map { it.paragraphIndex }).containsExactly(5, 6, 7).inOrder()
  }
}
