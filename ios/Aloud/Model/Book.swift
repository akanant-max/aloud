import Foundation

enum BookFormat: String, Codable, CaseIterable {
  case epub, txt, pdf, mobi, fb2, html, docx

  var label: String {
    switch self {
    case .epub: return "EPUB"
    case .txt: return "Text"
    case .pdf: return "PDF"
    case .mobi: return "Kindle"
    case .fb2: return "FictionBook"
    case .html: return "Web page"
    case .docx: return "Word"
    }
  }
}

/// Where listening has got to: a chapter, a paragraph in it, and a character offset into that
/// paragraph (UTF-16 units, as NSString counts them).
struct Position: Codable, Hashable {
  var chapter: Int = 0
  var paragraph: Int = 0
  var offset: Int = 0

  static let start = Position()
}

/// A book in the library. Its text lives in a separate file, loaded only when needed.
struct Book: Codable, Identifiable, Equatable {
  var id: UUID
  var title: String
  var author: String?
  var format: BookFormat
  /// SHA-256 of the imported file, so the same file is never imported twice.
  var fingerprint: String
  var originalFileName: String
  var hasCover: Bool
  var added: Date
  var lastPlayed: Date?
  var position: Position
  var completed: Bool
  var collection: String?
  var chapterCount: Int
  var paragraphCount: Int
  var wordCount: Int
  /// Words before the start of each chapter, for progress and time estimates.
  var chapterWordStarts: [Int]
  var chapterTitles: [String]

  var started: Bool { position != .start || lastPlayed != nil }

  /// Roughly how far through the book listening is, from 0 to 1.
  func progress(paragraphWords: Int = 0) -> Double {
    guard wordCount > 0, !completed else { return completed ? 1 : 0 }
    let before = position.chapter < chapterWordStarts.count ? chapterWordStarts[position.chapter] : 0
    return min(1, Double(before + paragraphWords) / Double(wordCount))
  }

  func chapterTitle(_ index: Int) -> String {
    if index < chapterTitles.count, !chapterTitles[index].isEmpty { return chapterTitles[index] }
    return "Part \(index + 1)"
  }
}

struct Chapter: Codable {
  var title: String?
  var paragraphs: [String]
}

/// A book's text.
struct BookContent: Codable {
  var chapters: [Chapter]
}

enum Words {
  static func count(_ text: String) -> Int {
    var count = 0
    var inWord = false
    for scalar in text.unicodeScalars {
      let isSpace = CharacterSet.whitespacesAndNewlines.contains(scalar)
      if isSpace {
        inWord = false
      } else if !inWord {
        inWord = true
        count += 1
      }
    }
    return count
  }
}
