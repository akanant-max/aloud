import Foundation
import PDFKit
import UIKit
import ZIPFoundation

struct ImportError: LocalizedError {
  let message: String
  init(_ message: String) { self.message = message }
  var errorDescription: String? { message }
}

/// What a parser finds in a file.
struct ParsedBook {
  var title: String?
  var author: String?
  var cover: Data?
  var chapters: [Chapter] = []

  private static let chapterSize = 200

  /// Adds a chapter, breaking an over-long one up so the chapter list stays navigable.
  mutating func add(_ title: String?, _ paragraphs: [String]) {
    let clean = paragraphs.filter { !$0.isEmpty }
    guard !clean.isEmpty else { return }
    var index = 0
    var start = 0
    while start < clean.count {
      let end = min(clean.count, start + Self.chapterSize)
      chapters.append(Chapter(title: index == 0 ? title : nil, paragraphs: Array(clean[start..<end])))
      start = end
      index += 1
    }
  }
}

enum Parsers {

  static let extensions: [String: BookFormat] = [
    "epub": .epub, "txt": .txt, "text": .txt, "md": .txt, "pdf": .pdf,
    "mobi": .mobi, "azw": .mobi, "azw3": .mobi, "prc": .mobi,
    "fb2": .fb2, "html": .html, "htm": .html, "xhtml": .html, "docx": .docx,
  ]

  static func format(of url: URL) -> BookFormat? { extensions[url.pathExtension.lowercased()] }

  static func parse(_ url: URL, as format: BookFormat) throws -> ParsedBook {
    var book: ParsedBook
    switch format {
    case .epub: book = try epub(url)
    case .txt: book = try txt(url)
    case .pdf: book = try pdf(url)
    case .mobi: book = try mobi(url)
    case .fb2: book = try fb2(url)
    case .html: book = try html(url)
    case .docx: book = try docx(url)
    }
    if book.chapters.isEmpty { throw ImportError("No readable text was found in this file.") }
    return book
  }

  // MARK: Text

  static func txt(_ url: URL) throws -> ParsedBook {
    let data = try Data(contentsOf: url, options: .mappedIfSafe)
    guard data.count < 120_000_000 else { throw ImportError("This file is too large to import.") }
    let text = TextTools.decode(data)
    var book = ParsedBook()
    var title: String?
    var paragraphs: [String] = []
    var current = ""
    var chapterIndex = 0

    func endParagraph() {
      let clean = TextTools.paragraph(current)
      current = ""
      if !clean.isEmpty { paragraphs.append(clean) }
    }
    func endChapter() {
      endParagraph()
      if !paragraphs.isEmpty {
        book.add(title, paragraphs)
        chapterIndex += 1
      }
      paragraphs = []
      title = nil
    }

    text.enumerateLines { line, _ in
      let trimmed = line.trimmingCharacters(in: .whitespaces)
      if trimmed.isEmpty {
        endParagraph()
        return
      }
      if looksLikeHeading(trimmed) {
        endChapter()
        title = trimmed
        return
      }
      if paragraphs.count >= 120 { endChapter() }
      if !current.isEmpty { current += " " }
      current += trimmed
    }
    endChapter()
    return book
  }

  private static let chapterWord = try! NSRegularExpression(
    pattern: #"^(chapter|part|book|section|prologue|epilogue|appendix)\b"#, options: .caseInsensitive)

  /// A short standalone line that reads like a chapter title. Deliberately conservative.
  static func looksLikeHeading(_ line: String) -> Bool {
    guard line.count <= 72 else { return false }
    if line.hasSuffix(".") || line.hasSuffix(",") || line.hasSuffix(";") { return false }
    if chapterWord.firstMatch(in: line, range: NSRange(location: 0, length: (line as NSString).length)) != nil { return true }
    let letters = line.filter { $0.isLetter }
    return letters.count >= 3 && letters.allSatisfy { $0.isUppercase }
  }

  // MARK: EPUB

  static func epub(_ url: URL) throws -> ParsedBook {
    let archive: Archive
    do {
      archive = try Archive(url: url, accessMode: .read)
    } catch {
      throw ImportError("This EPUB could not be opened. It may be damaged.")
    }
    let zip = Zip(archive)

    guard let container = zip.text("META-INF/container.xml"),
          let opfPath = Markup.tags(container, named: "rootfile").first?.attributes["full-path"],
          !opfPath.isEmpty,
          let opf = zip.text(opfPath) else {
      throw ImportError("This is not a valid EPUB: it has no package file.")
    }
    // Obfuscated fonts are common and harmless; encrypted chapters mean DRM.
    if let enc = zip.text("META-INF/encryption.xml") {
      let encrypted = Markup.tags(enc, named: "cipherreference").compactMap { $0.attributes["uri"]?.lowercased() }
      if encrypted.contains(where: { $0.hasSuffix(".xhtml") || $0.hasSuffix(".html") || $0.hasSuffix(".htm") }) {
        throw ImportError("This EPUB is DRM-protected and cannot be read.")
      }
    }
    let opfDir = (opfPath as NSString).deletingLastPathComponent

    var book = ParsedBook()
    book.title = Markup.firstText(opf, element: "title")
    book.author = Markup.firstText(opf, element: "creator")

    var manifest: [String: String] = [:]
    var media: [String: String] = [:]
    var order: [String] = []
    var coverHref: String?
    var navHref: String?
    for item in Markup.tags(opf, named: "item") {
      guard let id = item.attributes["id"], let href = item.attributes["href"], !id.isEmpty, !href.isEmpty else { continue }
      manifest[id] = href
      media[id] = item.attributes["media-type"] ?? ""
      order.append(id)
      let properties = item.attributes["properties"] ?? ""
      if properties.contains("cover-image") { coverHref = href }
      if properties.split(separator: " ").contains("nav") { navHref = href }
    }
    if coverHref == nil {
      if let coverId = Markup.tags(opf, named: "meta").first(where: { $0.attributes["name"] == "cover" })?.attributes["content"] {
        coverHref = manifest[coverId]
      }
    }
    if coverHref == nil {
      coverHref = order.first { id in
        (media[id] ?? "").hasPrefix("image/") && (id.lowercased().contains("cover") || (manifest[id] ?? "").lowercased().contains("cover"))
      }.flatMap { manifest[$0] }
    }
    if coverHref == nil {
      coverHref = order.first { (media[$0] ?? "").hasPrefix("image/") }.flatMap { manifest[$0] }
    }
    if let coverHref { book.cover = zip.data(resolve(opfDir, coverHref)) }

    let spineTag = Markup.tags(opf, named: "spine").first
    let ncxHref = spineTag?.attributes["toc"].flatMap { manifest[$0] }
      ?? order.first { media[$0] == "application/x-dtbncx+xml" }.flatMap { manifest[$0] }
    let spine = Markup.tags(opf, named: "itemref").compactMap { $0.attributes["idref"].flatMap { manifest[$0] } }
      .filter { $0 != navHref }
    guard !spine.isEmpty else { throw ImportError("This is not a valid EPUB: it lists no chapters.") }

    // Chapter titles from the contents list, keyed by the file they point at.
    var tocTitles: [String: String] = [:]
    if let ncxHref, let ncx = zip.text(resolve(opfDir, ncxHref)) {
      let ncxDir = (resolve(opfDir, ncxHref) as NSString).deletingLastPathComponent
      var label: String?
      var inLabel = false
      var labelText = ""
      Markup.scan(ncx) { token in
        switch token {
        case .tag(let tag):
          if tag.name == "navlabel" { inLabel = !tag.closing; if !tag.closing { labelText = "" } else { label = TextTools.paragraph(labelText) } }
          if tag.name == "content", !tag.closing, let src = tag.attributes["src"], let l = label, !l.isEmpty {
            let path = resolve(ncxDir, String(src.split(separator: "#", maxSplits: 1).first ?? ""))
            if tocTitles[path] == nil { tocTitles[path] = l }
            label = nil
          }
        case .text(let t):
          if inLabel { labelText += t }
        }
      }
    }
    if let navHref, let nav = zip.text(resolve(opfDir, navHref)) {
      let navDir = (resolve(opfDir, navHref) as NSString).deletingLastPathComponent
      var href: String?
      var text = ""
      Markup.scan(nav) { token in
        switch token {
        case .tag(let tag) where tag.name == "a":
          if tag.closing {
            if let h = href {
              let label = TextTools.paragraph(text)
              let path = resolve(navDir, String(h.split(separator: "#", maxSplits: 1).first ?? ""))
              if !label.isEmpty, !h.hasPrefix("#"), tocTitles[path] == nil { tocTitles[path] = label }
            }
            href = nil
          } else {
            href = tag.attributes["href"]
            text = ""
          }
        case .text(let t):
          if href != nil { text += t }
        default:
          break
        }
      }
    }

    for href in spine {
      try autoreleasepool {
        let path = resolve(opfDir, href)
        guard let html = zip.text(path) else { return }
        let paragraphs = Markup.paragraphs(html)
        guard !paragraphs.isEmpty else { return }
        let title = tocTitles[path] ?? Markup.firstHeading(html)
        book.add(title, paragraphs)
        if book.chapters.count > 20_000 { throw ImportError("This EPUB is too large to import.") }
      }
    }
    return book
  }

  /// Resolves an href against a directory, normalising `..` and percent-escapes.
  static func resolve(_ base: String, _ href: String) -> String {
    let decoded = href.removingPercentEncoding ?? href
    let combined = base.isEmpty ? decoded : base + "/" + decoded
    var parts: [Substring] = []
    for segment in combined.split(separator: "/", omittingEmptySubsequences: true) {
      if segment == "." { continue }
      if segment == ".." { if !parts.isEmpty { parts.removeLast() }; continue }
      parts.append(segment)
    }
    return parts.joined(separator: "/")
  }

  /// A ZIP archive read by path, forgiving of case differences.
  final class Zip {
    let archive: Archive
    private var lowerIndex: [String: Entry] = [:]

    init(_ archive: Archive) {
      self.archive = archive
      for entry in archive { lowerIndex[entry.path.lowercased()] = entry }
    }

    func has(_ path: String) -> Bool { entry(path) != nil }

    func entry(_ path: String) -> Entry? {
      archive[path] ?? lowerIndex[path.lowercased()]
    }

    func data(_ path: String) -> Data? {
      guard let entry = entry(path), entry.type == .file else { return nil }
      var data = Data()
      data.reserveCapacity(Int(min(entry.uncompressedSize, 64_000_000)))
      do {
        _ = try archive.extract(entry, skipCRC32: true) { data.append($0) }
      } catch {
        return nil
      }
      return data
    }

    func text(_ path: String) -> String? { data(path).map(TextTools.decode) }
  }

  // MARK: PDF

  static func pdf(_ url: URL) throws -> ParsedBook {
    guard let document = PDFDocument(url: url) else {
      throw ImportError("This PDF could not be opened. It may be damaged.")
    }
    if document.isLocked { throw ImportError("This PDF is password-protected.") }
    var book = ParsedBook()
    let attributes = document.documentAttributes ?? [:]
    book.title = (attributes[PDFDocumentAttribute.titleAttribute] as? String).flatMap { $0.isEmpty ? nil : $0 }
    book.author = (attributes[PDFDocumentAttribute.authorAttribute] as? String).flatMap { $0.isEmpty ? nil : $0 }

    let pageCount = document.pageCount
    guard pageCount > 0 else { throw ImportError("This PDF has no pages.") }

    if let first = document.page(at: 0) {
      let image = first.thumbnail(of: CGSize(width: 600, height: 900), for: .mediaBox)
      book.cover = image.jpegData(compressionQuality: 0.8)
    }

    // Chapters follow the PDF's own outline where it has one; otherwise every 20 pages.
    var starts: [(page: Int, title: String?)] = []
    if let root = document.outlineRoot, root.numberOfChildren > 1 {
      for i in 0..<root.numberOfChildren {
        guard let item = root.child(at: i), let page = item.destination?.page else { continue }
        let index = document.index(for: page)
        if index >= 0, index < pageCount, starts.last?.page != index {
          starts.append((index, item.label))
        }
      }
      starts.sort { $0.page < $1.page }
    }
    if starts.isEmpty || starts[0].page != 0 {
      starts.insert((page: 0, title: nil), at: 0)
    }
    if starts.count == 1 {
      starts = stride(from: 0, to: pageCount, by: 20).map { first -> (page: Int, title: String?) in
        (first, "Pages \(first + 1)–\(min(pageCount, first + 20))")
      }
    }

    var foundText = false
    for (k, start) in starts.enumerated() {
      let end = k + 1 < starts.count ? starts[k + 1].page : pageCount
      var lines: [String] = []
      for p in start.page..<max(start.page, end) {
        autoreleasepool {
          if let text = document.page(at: p)?.string {
            lines.append(contentsOf: text.components(separatedBy: .newlines))
            lines.append("")
          }
        }
      }
      let paragraphs = pdfParagraphs(lines)
      if !paragraphs.isEmpty { foundText = true }
      book.add(start.title, paragraphs)
    }
    if !foundText {
      throw ImportError("This PDF has no text layer — it is scanned images. Run it through OCR first.")
    }
    return book
  }

  /// Joins a PDF's lines back into paragraphs: a short line ending a sentence closes one.
  static func pdfParagraphs(_ lines: [String]) -> [String] {
    let lengths = lines.map { $0.trimmingCharacters(in: .whitespaces).count }.filter { $0 > 20 }.sorted()
    let typical = lengths.isEmpty ? 60 : lengths[lengths.count * 3 / 4]
    var result: [String] = []
    var current = ""
    func end() {
      let clean = TextTools.paragraph(TextTools.mendHyphenation(current))
      if !clean.isEmpty, !(clean.count <= 4 && clean.allSatisfy(\.isNumber)) { result.append(clean) }
      current = ""
    }
    for raw in lines {
      let line = raw.trimmingCharacters(in: .whitespaces)
      if line.isEmpty { continue }
      if !current.isEmpty { current += "\n" }
      current += line
      let last = line.last.map(String.init) ?? ""
      let endsSentence = ".!?:\"”’)»".contains(last)
      if endsSentence && Double(line.count) < Double(typical) * 0.8 { end() }
    }
    end()
    return result
  }

  // MARK: MOBI / AZW3

  static func mobi(_ url: URL) throws -> ParsedBook {
    let data = try Data(contentsOf: url, options: .mappedIfSafe)
    let bytes = [UInt8](data)
    guard bytes.count >= 78 else { throw ImportError("This is not a valid Kindle book.") }
    func u16(_ at: Int) -> Int { at + 1 < bytes.count ? Int(bytes[at]) << 8 | Int(bytes[at + 1]) : 0 }
    func u32(_ at: Int) -> Int {
      guard at + 3 < bytes.count else { return 0 }
      return Int(bytes[at]) << 24 | Int(bytes[at + 1]) << 16 | Int(bytes[at + 2]) << 8 | Int(bytes[at + 3])
    }

    let recordCount = u16(76)
    guard recordCount >= 2 else { throw ImportError("This book has no text in it.") }
    var offsets: [Int] = (0..<recordCount).map { u32(78 + $0 * 8) }
    offsets.append(bytes.count)
    func record(_ index: Int) throws -> ArraySlice<UInt8> {
      let start = offsets[index], end = offsets[index + 1]
      guard start >= 0, end >= start, end <= bytes.count else { throw ImportError("This book's file is damaged.") }
      return bytes[start..<end]
    }

    let header = Array(try record(0))
    guard header.count >= 16 else { throw ImportError("This book's file is damaged.") }
    func h16(_ at: Int) -> Int { at + 1 < header.count ? Int(header[at]) << 8 | Int(header[at + 1]) : 0 }
    func h32(_ at: Int) -> Int {
      guard at + 3 < header.count else { return 0 }
      return Int(header[at]) << 24 | Int(header[at + 1]) << 16 | Int(header[at + 2]) << 8 | Int(header[at + 3])
    }
    let compression = h16(0)
    let textRecords = h16(8)
    let encryption = h16(12)
    if encryption != 0 { throw ImportError("This book is DRM-protected and cannot be read.") }
    if compression != 1 && compression != 2 {
      throw ImportError("This book uses a Kindle compression Aloud does not read. Converting it to EPUB first will work.")
    }

    var encoding: String.Encoding = .windowsCP1252
    var extraFlags = 0
    var book = ParsedBook()
    if header.count >= 32, String(bytes: header[16..<20], encoding: .ascii) == "MOBI" {
      let headerLength = h32(20)
      if h32(28) == 65001 { encoding = .utf8 }
      if header.count >= 92 {
        let nameOffset = h32(84), nameLength = h32(88)
        if nameOffset > 0, nameLength > 0, nameLength <= 1024, nameOffset + nameLength <= header.count {
          book.title = String(bytes: header[nameOffset..<(nameOffset + nameLength)], encoding: encoding)
        }
      }
      if headerLength >= 0xE4, header.count >= 0xF4 { extraFlags = h16(0xF2) }
      let exth = 16 + headerLength
      if header.count >= 0x84, (h32(0x80) & 0x40) != 0, exth + 12 <= header.count,
         String(bytes: header[exth..<(exth + 4)], encoding: .ascii) == "EXTH" {
        var position = exth + 12
        for _ in 0..<min(h32(exth + 8), 500) {
          guard position + 8 <= header.count else { break }
          let type = h32(position), size = h32(position + 4)
          guard size >= 8, position + size <= header.count else { break }
          let value = String(bytes: header[(position + 8)..<(position + size)], encoding: encoding)
          if type == 100, book.author == nil { book.author = value }
          if type == 503 { book.title = value }
          position += size
        }
      }
    }

    var text: [UInt8] = []
    for index in 1...max(1, textRecords) where index < recordCount {
      let raw = Array(try record(index))
      let size = max(0, raw.count - trailingBytes(raw, flags: extraFlags))
      if compression == 2 {
        palmDocDecompress(raw, size: size, into: &text)
      } else {
        text.append(contentsOf: raw[0..<size])
      }
    }
    var markup = String(bytes: text, encoding: encoding) ?? String(decoding: text, as: UTF8.self)
    if let last = markup.range(of: "</html>", options: [.caseInsensitive, .backwards]),
       markup.distance(from: markup.startIndex, to: last.lowerBound) > markup.count / 2 {
      markup = String(markup[..<last.upperBound])
    }
    let pages = markup.components(separatedBy: "<mbp:pagebreak")
    for page in pages {
      autoreleasepool {
        let paragraphs = Markup.paragraphs(page)
        if !paragraphs.isEmpty { book.add(Markup.firstHeading(page), paragraphs) }
      }
    }
    return book
  }

  private static func trailingBytes(_ data: [UInt8], flags: Int) -> Int {
    var end = data.count
    if flags > 1 {
      for bit in stride(from: 15, through: 1, by: -1) where (flags & (1 << bit)) != 0 {
        var value = 0
        for i in max(0, end - 4)..<end {
          let byte = Int(data[i])
          if (byte & 0x80) != 0 { value = 0 }
          value = (value << 7) | (byte & 0x7F)
        }
        end = max(0, end - value)
      }
    }
    if (flags & 1) != 0, end > 0 { end -= Int(data[end - 1] & 0x3) + 1 }
    return data.count - max(0, end)
  }

  private static func palmDocDecompress(_ data: [UInt8], size: Int, into out: inout [UInt8]) {
    var produced: [UInt8] = []
    produced.reserveCapacity(4096)
    var i = 0
    while i < size {
      let c = Int(data[i]); i += 1
      if c == 0 || (0x09...0x7F).contains(c) {
        produced.append(UInt8(c))
      } else if (0x01...0x08).contains(c) {
        var n = c
        while n > 0 && i < size { produced.append(data[i]); i += 1; n -= 1 }
      } else if c >= 0xC0 {
        produced.append(32)
        produced.append(UInt8(c ^ 0x80))
      } else {
        guard i < size else { break }
        let pair = (c << 8) | Int(data[i]); i += 1
        let distance = (pair >> 3) & 0x7FF
        let count = (pair & 0x7) + 3
        guard distance > 0, distance <= produced.count else { continue }
        for _ in 0..<count { produced.append(produced[produced.count - distance]) }
      }
    }
    out.append(contentsOf: produced)
  }

  // MARK: FictionBook

  static func fb2(_ url: URL) throws -> ParsedBook {
    var xml = TextTools.decode(try Data(contentsOf: url))
    xml = xml.replacingOccurrences(of: #"(?s)<binary\b.*?</binary\s*>"#, with: " ", options: [.regularExpression, .caseInsensitive])
    var book = ParsedBook()
    book.title = Markup.firstText(xml, element: "book-title")
    if let authorBlock = xml.range(of: #"(?s)<author[^>]*>.*?</author\s*>"#, options: .regularExpression) {
      let plain = Markup.plain(String(xml[authorBlock]))
      book.author = plain.isEmpty ? nil : plain
    }
    let body = xml.range(of: "<body").map { String(xml[$0.lowerBound...]) } ?? xml
    for section in body.components(separatedBy: "<section") {
      let heading = section.range(of: #"(?s)<title[^>]*>.*?</title\s*>"#, options: .regularExpression)
        .map { Markup.plain(String(section[$0])) }
      book.add(heading.flatMap { $0.isEmpty || $0.count > 120 ? nil : $0 }, Markup.paragraphs(section))
    }
    return book
  }

  // MARK: HTML

  static func html(_ url: URL) throws -> ParsedBook {
    let html = TextTools.decode(try Data(contentsOf: url))
    var book = ParsedBook()
    book.title = Markup.firstText(html, element: "title")
    let pieces = html.components(separatedBy: try NSRegularExpression(pattern: "(?=<h[12]\\b)", options: .caseInsensitive))
    for piece in pieces {
      book.add(Markup.firstHeading(piece), Markup.paragraphs(piece))
    }
    return book
  }

  // MARK: Word

  static func docx(_ url: URL) throws -> ParsedBook {
    let archive: Archive
    do {
      archive = try Archive(url: url, accessMode: .read)
    } catch {
      throw ImportError("This Word document could not be opened.")
    }
    let zip = Zip(archive)
    guard let xml = zip.text("word/document.xml") else { throw ImportError("This is not a valid Word document.") }
    var book = ParsedBook()
    if let core = zip.text("docProps/core.xml") {
      book.title = Markup.firstText(core, element: "title")
      book.author = Markup.firstText(core, element: "creator")
    }
    var chapterTitle: String?
    var pending: [String] = []
    var paragraph = ""
    var isHeading = false
    var inText = false
    Markup.scan(xml) { token in
      switch token {
      case .tag(let tag):
        switch tag.name {
        case "p":
          if tag.closing || tag.selfClosing {
            let text = TextTools.paragraph(paragraph)
            if !text.isEmpty {
              if isHeading && text.count <= 120 {
                if !pending.isEmpty { book.add(chapterTitle, pending); pending = [] }
                chapterTitle = text
              } else {
                pending.append(text)
              }
            }
            paragraph = ""
            isHeading = false
          } else {
            paragraph = ""
            isHeading = false
          }
        case "pstyle":
          let value = (tag.attributes["val"] ?? "").lowercased()
          if value.hasPrefix("heading") || value.hasPrefix("title") { isHeading = true }
        case "t":
          inText = !tag.closing && !tag.selfClosing
        case "tab", "br":
          paragraph += " "
        default:
          break
        }
      case .text(let t):
        if inText { paragraph += t }
      }
    }
    if !pending.isEmpty { book.add(chapterTitle, pending) }
    return book
  }
}

private extension String {
  func components(separatedBy regex: NSRegularExpression) -> [String] {
    let ns = self as NSString
    var pieces: [String] = []
    var last = 0
    for match in regex.matches(in: self, range: NSRange(location: 0, length: ns.length)) {
      if match.range.location > last {
        pieces.append(ns.substring(with: NSRange(location: last, length: match.range.location - last)))
      }
      last = match.range.location
    }
    pieces.append(ns.substring(from: last))
    return pieces
  }
}
