package com.akhouri.aloud.tts

import java.text.BreakIterator

/**
 * One utterance handed to the speech engine, and where it came from.
 *
 * [startOffset] and [endOffset] are character positions in the original paragraph. That mapping
 * is what lets narration resume mid-paragraph, highlight the right text, and survive the engine
 * finishing one chunk and starting the next.
 */
data class SpeechChunk(
  val paragraphIndex: Int,
  val startOffset: Int,
  val endOffset: Int,
  val text: String,
) {
  val length: Int get() = endOffset - startOffset
}

/**
 * Splits paragraphs into utterances.
 *
 * Engines accept a few thousand characters at most, and a whole chapter in one request would
 * make pausing unresponsive and position tracking impossible. Splitting happens at sentence
 * boundaries where it can, word boundaries where it must, and never inside a word.
 */
object Chunker {

  /**
   * Comfortably under every engine's limit while still long enough that the joins between
   * utterances are not audible.
   */
  const val DEFAULT_MAX_CHARS = 400

  /** The floor, so a pathological setting cannot produce one-character utterances. */
  private const val MIN_MAX_CHARS = 80

  /**
   * Chunks one paragraph.
   *
   * [fromOffset] starts partway in, which is how a resume lands exactly where playback stopped
   * rather than at the top of the paragraph.
   */
  fun chunk(
    paragraphIndex: Int,
    text: String,
    maxChars: Int = DEFAULT_MAX_CHARS,
    fromOffset: Int = 0,
  ): List<SpeechChunk> {
    val limit = maxChars.coerceAtLeast(MIN_MAX_CHARS)
    val start = fromOffset.coerceIn(0, text.length)
    if (start >= text.length) return emptyList()

    val body = text.substring(start)
    if (body.isBlank()) return emptyList()

    if (body.length <= limit) {
      return listOf(SpeechChunk(paragraphIndex, start, text.length, body))
    }

    val chunks = ArrayList<SpeechChunk>()
    var cursor = 0

    for (sentence in boundaries(body, BreakIterator.getSentenceInstance())) {
      val piece = body.substring(sentence.first, sentence.second)
      if (piece.isBlank()) continue

      when {
        // The sentence alone is too long: fall back to packing whole words.
        piece.length > limit -> {
          flush(chunks, paragraphIndex, body, start, cursor, sentence.first)
          cursor = sentence.second
          chunks += packWords(paragraphIndex, body, start, sentence.first, sentence.second, limit)
        }
        // Adding it would overflow the chunk being built: close that one first.
        sentence.second - cursor > limit -> {
          flush(chunks, paragraphIndex, body, start, cursor, sentence.first)
          cursor = sentence.first
        }
      }
    }

    flush(chunks, paragraphIndex, body, start, cursor, body.length)
    return chunks.filter { it.text.isNotBlank() }
  }

  /** Chunks a run of paragraphs, keeping each paragraph's chunks in document order. */
  fun chunkAll(
    paragraphs: List<Pair<Int, String>>,
    maxChars: Int = DEFAULT_MAX_CHARS,
  ): List<SpeechChunk> = paragraphs.flatMap { (index, text) -> chunk(index, text, maxChars) }

  private fun flush(
    into: MutableList<SpeechChunk>,
    paragraphIndex: Int,
    body: String,
    base: Int,
    from: Int,
    to: Int,
  ) {
    if (to <= from) return
    val piece = body.substring(from, to)
    if (piece.isBlank()) return
    into += SpeechChunk(
      paragraphIndex = paragraphIndex,
      startOffset = base + from,
      endOffset = base + to,
      text = piece.trim(),
    )
  }

  /**
   * Last resort for a sentence longer than the limit — a run-on, a list rendered as one
   * sentence, a table flattened into prose. Packs whole words, so nothing is ever cut mid-word.
   */
  private fun packWords(
    paragraphIndex: Int,
    body: String,
    base: Int,
    from: Int,
    to: Int,
    limit: Int,
  ): List<SpeechChunk> {
    val result = ArrayList<SpeechChunk>()
    val segment = body.substring(from, to)
    var chunkStart = 0
    var cursor = 0

    for (word in boundaries(segment, BreakIterator.getWordInstance())) {
      if (word.second - chunkStart > limit && cursor > chunkStart) {
        flush(result, paragraphIndex, body, base, from + chunkStart, from + cursor)
        chunkStart = cursor
      }
      cursor = word.second
    }
    flush(result, paragraphIndex, body, base, from + chunkStart, from + segment.length)

    return result
  }

  /** Walks a [BreakIterator] into (start, end) pairs. */
  private fun boundaries(text: String, iterator: BreakIterator): List<Pair<Int, Int>> {
    iterator.setText(text)
    val spans = ArrayList<Pair<Int, Int>>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
      spans += start to end
      start = end
      end = iterator.next()
    }
    return spans
  }
}
