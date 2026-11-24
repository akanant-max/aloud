import Foundation

/// One stretch of speech and where it came from in the book. Offsets are UTF-16 positions in
/// the paragraph, which is what lets narration resume mid-paragraph.
struct SpeechChunk: Equatable {
  var chapter: Int
  var paragraph: Int
  var start: Int
  var end: Int
  var text: String

  var words: Int { Words.count(text) }
}

/// Splits paragraphs into utterances at sentence boundaries where it can, word boundaries where
/// it must, never inside a word.
enum Chunker {
  static let maxChars = 320
  /// Kept short so the first sound after pressing play comes quickly.
  static let firstChunkChars = 140

  static func chunks(chapter: Int, paragraph: Int, text: String, from offset: Int = 0, limit: Int = maxChars) -> [SpeechChunk] {
    let ns = text as NSString
    let start = max(0, min(offset, ns.length))
    guard start < ns.length else { return [] }
    var sentences: [NSRange] = []
    ns.enumerateSubstrings(in: NSRange(location: start, length: ns.length - start), options: [.bySentences, .substringNotRequired]) { _, _, enclosing, _ in
      sentences.append(enclosing)
    }
    if sentences.isEmpty { sentences = [NSRange(location: start, length: ns.length - start)] }

    var result: [SpeechChunk] = []
    var chunkStart = sentences[0].location
    var chunkEnd = chunkStart

    func flush() {
      guard chunkEnd > chunkStart else { return }
      let piece = ns.substring(with: NSRange(location: chunkStart, length: chunkEnd - chunkStart))
        .trimmingCharacters(in: .whitespacesAndNewlines)
      if !piece.isEmpty {
        result.append(SpeechChunk(chapter: chapter, paragraph: paragraph, start: chunkStart, end: chunkEnd, text: piece))
      }
      chunkStart = chunkEnd
    }

    for sentence in sentences {
      let currentLimit = result.isEmpty ? limit : limit
      if sentence.length > currentLimit {
        flush()
        chunkStart = sentence.location
        chunkEnd = sentence.location
        packWords(ns, sentence, limit: currentLimit, chapter: chapter, paragraph: paragraph, into: &result)
        chunkStart = NSMaxRange(sentence)
        chunkEnd = chunkStart
        continue
      }
      if NSMaxRange(sentence) - chunkStart > currentLimit {
        flush()
        chunkStart = sentence.location
      }
      chunkEnd = NSMaxRange(sentence)
    }
    flush()
    return result
  }

  private static func packWords(_ ns: NSString, _ range: NSRange, limit: Int, chapter: Int, paragraph: Int, into result: inout [SpeechChunk]) {
    var words: [NSRange] = []
    ns.enumerateSubstrings(in: range, options: [.byWords, .substringNotRequired]) { _, wordRange, _, _ in
      words.append(wordRange)
    }
    var start = range.location
    var end = range.location
    func flush() {
      guard end > start else { return }
      let piece = ns.substring(with: NSRange(location: start, length: end - start)).trimmingCharacters(in: .whitespacesAndNewlines)
      if !piece.isEmpty {
        result.append(SpeechChunk(chapter: chapter, paragraph: paragraph, start: start, end: end, text: piece))
      }
      start = end
    }
    for word in words {
      if NSMaxRange(word) - start > limit, end > start { flush() }
      end = NSMaxRange(word)
    }
    end = NSMaxRange(range)
    flush()
  }
}
