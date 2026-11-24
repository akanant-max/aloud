import Foundation
import SwiftUI
import UIKit

/// Where Aloud keeps its files.
enum Paths {
  static let support: URL = {
    let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    let url = base.appendingPathComponent("Aloud", isDirectory: true)
    try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    return url
  }()

  static let index = support.appendingPathComponent("library.json")
  static let books = directory("books")
  static let covers = directory("covers")
  static let voices = directory("voices")

  /// Shown in the Files app as "On My iPad › Aloud"; books dropped there are imported.
  static let inbox: URL = {
    let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    let url = docs.appendingPathComponent("Add books here", isDirectory: true)
    try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    return url
  }()

  static func directory(_ name: String) -> URL {
    let url = support.appendingPathComponent(name, isDirectory: true)
    try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    return url
  }

  static func content(_ id: UUID) -> URL { books.appendingPathComponent("\(id.uuidString).json") }
  static func cover(_ id: UUID) -> URL { covers.appendingPathComponent("\(id.uuidString).jpg") }
}

enum LibrarySort: String, CaseIterable, Identifiable {
  case recent, title, author, added
  var id: String { rawValue }
  var label: String {
    switch self {
    case .recent: return "Recently played"
    case .title: return "Title"
    case .author: return "Author"
    case .added: return "Recently added"
    }
  }
}

/// The library: every book's details, kept in one small file, with each book's text apart.
@MainActor
final class Library: ObservableObject {

  @Published private(set) var books: [Book] = []
  /// Bumped when a cover changes, so views showing it reload.
  @Published private(set) var coverVersion = 0

  private var contentCache: [UUID: BookContent] = [:]
  private var cacheOrder: [UUID] = []
  private var saveTask: Task<Void, Never>?

  init() {
    load()
  }

  // MARK: Reading

  func book(_ id: UUID) -> Book? { books.first { $0.id == id } }

  func contains(fingerprint: String) -> Book? { books.first { $0.fingerprint == fingerprint } }

  var collections: [String] {
    Array(Set(books.compactMap { $0.collection })).sorted { $0.localizedStandardCompare($1) == .orderedAscending }
  }

  func content(for id: UUID) -> BookContent? {
    if let cached = contentCache[id] { return cached }
    guard let data = try? Data(contentsOf: Paths.content(id)),
          let content = try? JSONDecoder().decode(BookContent.self, from: data) else { return nil }
    remember(id, content)
    return content
  }

  func cover(for id: UUID) -> UIImage? {
    UIImage(contentsOfFile: Paths.cover(id).path)
  }

  func sorted(_ list: [Book], by sort: LibrarySort) -> [Book] {
    switch sort {
    case .recent:
      return list.sorted { ($0.lastPlayed ?? $0.added) > ($1.lastPlayed ?? $1.added) }
    case .title:
      return list.sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
    case .author:
      return list.sorted {
        let a = $0.author ?? "~", b = $1.author ?? "~"
        if a == b { return $0.title.localizedStandardCompare($1.title) == .orderedAscending }
        return a.localizedStandardCompare(b) == .orderedAscending
      }
    case .added:
      return list.sorted { $0.added > $1.added }
    }
  }

  // MARK: Changing

  func add(_ book: Book, content: BookContent, cover: Data?) throws {
    let data = try JSONEncoder().encode(content)
    try data.write(to: Paths.content(book.id), options: .atomic)
    if let cover, let image = UIImage(data: cover) {
      try? Self.jpeg(image)?.write(to: Paths.cover(book.id), options: .atomic)
    }
    var stored = book
    stored.hasCover = FileManager.default.fileExists(atPath: Paths.cover(book.id).path)
    books.append(stored)
    remember(book.id, content)
    save()
  }

  func update(_ id: UUID, _ change: (inout Book) -> Void) {
    guard let index = books.firstIndex(where: { $0.id == id }) else { return }
    var book = books[index]
    change(&book)
    if book != books[index] {
      books[index] = book
      save()
    }
  }

  func delete(_ ids: Set<UUID>) {
    for id in ids {
      try? FileManager.default.removeItem(at: Paths.content(id))
      try? FileManager.default.removeItem(at: Paths.cover(id))
      contentCache[id] = nil
    }
    books.removeAll { ids.contains($0.id) }
    save()
  }

  func setCover(_ id: UUID, image: UIImage?) {
    if let image, let data = Self.jpeg(image) {
      try? data.write(to: Paths.cover(id), options: .atomic)
    } else {
      try? FileManager.default.removeItem(at: Paths.cover(id))
    }
    update(id) { $0.hasCover = image != nil }
    coverVersion += 1
  }

  func move(_ ids: Set<UUID>, toCollection name: String?) {
    let clean = name?.trimmingCharacters(in: .whitespacesAndNewlines)
    for id in ids {
      update(id) { $0.collection = (clean?.isEmpty ?? true) ? nil : clean }
    }
  }

  func renameCollection(_ old: String, to new: String) {
    let clean = new.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !clean.isEmpty else { return }
    for book in books where book.collection == old {
      update(book.id) { $0.collection = clean }
    }
  }

  /// Writes the library soon, folding a burst of changes into one write.
  func save() {
    saveTask?.cancel()
    let snapshot = books
    saveTask = Task.detached(priority: .utility) {
      try? await Task.sleep(nanoseconds: 300_000_000)
      if Task.isCancelled { return }
      if let data = try? JSONEncoder().encode(snapshot) {
        try? data.write(to: Paths.index, options: .atomic)
      }
    }
  }

  /// Writes immediately, for when the app is about to be suspended.
  func saveNow() {
    saveTask?.cancel()
    if let data = try? JSONEncoder().encode(books) {
      try? data.write(to: Paths.index, options: .atomic)
    }
  }

  // MARK: Private

  private func load() {
    guard let data = try? Data(contentsOf: Paths.index),
          let list = try? JSONDecoder().decode([Book].self, from: data) else { return }
    books = list
  }

  private func remember(_ id: UUID, _ content: BookContent) {
    contentCache[id] = content
    cacheOrder.removeAll { $0 == id }
    cacheOrder.append(id)
    while cacheOrder.count > 3 {
      let old = cacheOrder.removeFirst()
      contentCache[old] = nil
    }
  }

  static func jpeg(_ image: UIImage) -> Data? {
    let maxSide: CGFloat = 900
    let size = image.size
    let scale = min(1, maxSide / max(size.width, size.height))
    let target = CGSize(width: max(1, size.width * scale), height: max(1, size.height * scale))
    let format = UIGraphicsImageRendererFormat()
    format.scale = 1
    let resized = UIGraphicsImageRenderer(size: target, format: format).image { _ in
      image.draw(in: CGRect(origin: .zero, size: target))
    }
    return resized.jpegData(compressionQuality: 0.85)
  }
}
