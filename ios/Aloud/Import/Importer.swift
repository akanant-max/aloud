import CryptoKit
import Foundation
import UniformTypeIdentifiers

/// Imports books one at a time, reporting what happened in one short message at the end.
@MainActor
final class Importer: ObservableObject {

  @Published private(set) var status: String?
  @Published var message: String?

  private var queue: [URL] = []
  private var running = false
  private var added = 0
  private var duplicates = 0
  private var failures: [String] = []

  private weak var library: Library?

  func attach(_ library: Library) { self.library = library }

  /// Accepts files and folders, as picked, dropped or opened from another app.
  func enqueue(_ urls: [URL]) {
    for url in urls { queue.append(contentsOf: expand(url)) }
    if !running { Task { await run() } }
  }

  /// Imports anything put in the app's "Add books here" folder in the Files app.
  func scanInbox() {
    let files = (try? FileManager.default.contentsOfDirectory(at: Paths.inbox, includingPropertiesForKeys: nil)) ?? []
    let books = files.filter { Parsers.format(of: $0) != nil }
    guard !books.isEmpty else { return }
    queue.append(contentsOf: books)
    if !running { Task { await run() } }
  }

  private func expand(_ url: URL, depth: Int = 0) -> [URL] {
    let scoped = url.startAccessingSecurityScopedResource()
    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
    var isDirectory: ObjCBool = false
    guard FileManager.default.fileExists(atPath: url.path, isDirectory: &isDirectory) else { return [url] }
    guard isDirectory.boolValue else { return [url] }
    guard depth < 4 else { return [] }
    // A folder's files are copied out now, while access to it is granted.
    let children = (try? FileManager.default.contentsOfDirectory(at: url, includingPropertiesForKeys: nil)) ?? []
    var found: [URL] = []
    for child in children.sorted(by: { $0.lastPathComponent.localizedStandardCompare($1.lastPathComponent) == .orderedAscending }) {
      var childIsDirectory: ObjCBool = false
      FileManager.default.fileExists(atPath: child.path, isDirectory: &childIsDirectory)
      if childIsDirectory.boolValue {
        found.append(contentsOf: expand(child, depth: depth + 1))
      } else if Parsers.format(of: child) != nil, let copy = copyToTemp(child) {
        found.append(copy)
      }
    }
    return found
  }

  private func copyToTemp(_ url: URL) -> URL? {
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent("import-\(UUID().uuidString)", isDirectory: true)
    do {
      try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
      let target = dir.appendingPathComponent(url.lastPathComponent)
      try FileManager.default.copyItem(at: url, to: target)
      return target
    } catch {
      return nil
    }
  }

  private func run() async {
    running = true
    added = 0
    duplicates = 0
    failures = []
    var done = 0
    while !queue.isEmpty {
      let url = queue.removeFirst()
      done += 1
      let total = done + queue.count
      status = total > 1 ? "Adding \(done) of \(total): \(url.deletingPathExtension().lastPathComponent)"
                         : "Adding \(url.deletingPathExtension().lastPathComponent)"
      await importOne(url)
    }
    status = nil
    running = false
    var parts: [String] = []
    if added > 0 { parts.append(added == 1 ? "Added 1 book" : "Added \(added) books") }
    if duplicates > 0 { parts.append(duplicates == 1 ? "1 was already in the library" : "\(duplicates) were already in the library") }
    if !failures.isEmpty {
      parts.append(failures.count == 1 ? failures[0] : "\(failures.count) could not be read (\(failures[0]))")
    }
    message = parts.joined(separator: ". ")
  }

  private func importOne(_ source: URL) async {
    guard let library else { return }
    let name = source.lastPathComponent
    guard let format = Parsers.format(of: source) else {
      failures.append("\(name): this kind of file is not supported")
      return
    }
    let fromInbox = source.path.hasPrefix(Paths.inbox.path)

    // Work on a private copy, so the original can go away while it is read.
    let scoped = source.startAccessingSecurityScopedResource()
    let isTemp = source.deletingLastPathComponent().lastPathComponent.hasPrefix("import-")
    let local = isTemp ? source : copyToTemp(source)
    if scoped { source.stopAccessingSecurityScopedResource() }
    guard let local else {
      failures.append("\(name) could not be opened")
      return
    }
    defer {
      try? FileManager.default.removeItem(at: local.deletingLastPathComponent())
      if fromInbox { try? FileManager.default.removeItem(at: source) }
    }

    let fingerprint: String
    do {
      fingerprint = try await Task.detached(priority: .userInitiated) { try Self.hash(local) }.value
    } catch {
      failures.append("\(name) could not be read")
      return
    }
    if library.contains(fingerprint: fingerprint) != nil {
      duplicates += 1
      return
    }

    do {
      let parsed = try await Task.detached(priority: .userInitiated) { try Parsers.parse(local, as: format) }.value
      let (book, content) = Self.makeBook(parsed, fileName: name, format: format, fingerprint: fingerprint)
      try library.add(book, content: content, cover: parsed.cover)
      added += 1
    } catch {
      failures.append("\(name): \(error.localizedDescription)")
    }
  }

  nonisolated static func hash(_ url: URL) throws -> String {
    let handle = try FileHandle(forReadingFrom: url)
    defer { try? handle.close() }
    var hasher = SHA256()
    while true {
      let chunk = try handle.read(upToCount: 1 << 20) ?? Data()
      if chunk.isEmpty { break }
      hasher.update(data: chunk)
    }
    return hasher.finalize().map { String(format: "%02x", $0) }.joined()
  }

  nonisolated static func makeBook(_ parsed: ParsedBook, fileName: String, format: BookFormat, fingerprint: String) -> (Book, BookContent) {
    var wordStarts: [Int] = []
    var words = 0
    var paragraphs = 0
    for chapter in parsed.chapters {
      wordStarts.append(words)
      for p in chapter.paragraphs { words += Words.count(p) }
      paragraphs += chapter.paragraphs.count
    }
    let cleanTitle = parsed.title.map(TitleCleaner.clean).flatMap { $0.isEmpty ? nil : $0 }
    let book = Book(
      id: UUID(),
      title: cleanTitle ?? TitleCleaner.fromFileName(fileName),
      author: parsed.author.map(TextTools.paragraph).flatMap { $0.isEmpty ? nil : $0 },
      format: format,
      fingerprint: fingerprint,
      originalFileName: fileName,
      hasCover: false,
      added: Date(),
      lastPlayed: nil,
      position: .start,
      completed: false,
      collection: nil,
      chapterCount: parsed.chapters.count,
      paragraphCount: paragraphs,
      wordCount: words,
      chapterWordStarts: wordStarts,
      chapterTitles: parsed.chapters.map { $0.title ?? "" }
    )
    return (book, BookContent(chapters: parsed.chapters))
  }
}

/// Turns a download-site file name into something that reads like a title.
enum TitleCleaner {
  static func fromFileName(_ name: String) -> String {
    let base = (name as NSString).deletingPathExtension
    let clean = clean(base)
    return clean.isEmpty ? "Untitled" : clean
  }

  static func clean(_ raw: String) -> String {
    var text = raw
    let siteTags = #"(_?OceanofPDF\.com_?|\(?z-lib(rary)?(\.org)?\)?|\(?libgen(\.\w+)?\)?|--? ?Anna.?s Archive|\(?pdfdrive(\.com)?\)?|www\.\S+\.(com|org|net))"#
    text = text.replacingOccurrences(of: siteTags, with: " ", options: [.regularExpression, .caseInsensitive])
    text = text.replacingOccurrences(of: #"^[0-9a-fA-F]{6,}[-_ ]+"#, with: "", options: .regularExpression)
    text = text.replacingOccurrences(of: "_", with: " ")
    text = text.replacingOccurrences(of: #"\s{2,}"#, with: " ", options: .regularExpression)
    return text.trimmingCharacters(in: CharacterSet(charactersIn: " -._"))
  }
}
