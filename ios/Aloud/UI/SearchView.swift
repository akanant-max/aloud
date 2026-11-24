import SwiftUI

/// Finds books by title or author, and passages inside every book.
struct SearchView: View {
  @EnvironmentObject private var library: Library
  @Binding var path: NavigationPath

  @State private var query = ""
  @State private var hits: [Hit] = []
  @State private var searching = false
  @State private var task: Task<Void, Never>?

  struct Hit: Identifiable {
    let id = UUID()
    let book: UUID
    let title: String
    let position: Position
    let snippet: String
  }

  var body: some View {
    let trimmed = query.trimmingCharacters(in: .whitespaces)
    let books = trimmed.isEmpty ? [] : library.books.filter {
      $0.title.localizedCaseInsensitiveContains(trimmed) || ($0.author?.localizedCaseInsensitiveContains(trimmed) ?? false)
    }
    List {
      if !books.isEmpty {
        Section("Books") {
          ForEach(books) { book in
            Button { path.append(Route.book(book.id)) } label: {
              HStack(spacing: 12) {
                CoverView(book: book, cornerRadius: 4).frame(width: 34)
                VStack(alignment: .leading) {
                  Text(book.title).foregroundStyle(.primary)
                  if let author = book.author { Text(author).font(.caption).foregroundStyle(.secondary) }
                }
              }
            }
          }
        }
      }
      if !hits.isEmpty || searching {
        Section(searching ? "In the books (searching…)" : "In the books") {
          ForEach(hits) { hit in
            Button { path.append(Route.reader(hit.book, hit.position)) } label: {
              VStack(alignment: .leading, spacing: 4) {
                Text(hit.title).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                Text(hit.snippet).foregroundStyle(.primary).lineLimit(3)
              }
            }
          }
        }
      }
      if !trimmed.isEmpty && books.isEmpty && hits.isEmpty && !searching {
        Text("Nothing found for “\(trimmed)”.").foregroundStyle(.secondary)
      }
    }
    .navigationTitle("Search")
    .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Titles, authors and words inside books")
    .onChange(of: query) { _, _ in schedule() }
  }

  private func schedule() {
    task?.cancel()
    let q = query.trimmingCharacters(in: .whitespaces)
    hits = []
    guard q.count >= 3 else { searching = false; return }
    let catalogue = library.books.map { ($0.id, $0.title) }
    searching = true
    task = Task {
      try? await Task.sleep(nanoseconds: 350_000_000)
      if Task.isCancelled { return }
      for (id, title) in catalogue {
        if Task.isCancelled { return }
        let found = await Task.detached(priority: .userInitiated) { Self.search(id: id, title: title, query: q) }.value
        if Task.isCancelled { return }
        hits.append(contentsOf: found)
        if hits.count >= 300 { break }
      }
      searching = false
    }
  }

  nonisolated private static func search(id: UUID, title: String, query: String) -> [Hit] {
    guard let data = try? Data(contentsOf: Paths.content(id)),
          let content = try? JSONDecoder().decode(BookContent.self, from: data) else { return [] }
    var found: [Hit] = []
    for (c, chapter) in content.chapters.enumerated() {
      for (p, paragraph) in chapter.paragraphs.enumerated() {
        guard let range = paragraph.range(of: query, options: [.caseInsensitive, .diacriticInsensitive]) else { continue }
        let start = paragraph.index(range.lowerBound, offsetBy: -60, limitedBy: paragraph.startIndex) ?? paragraph.startIndex
        let end = paragraph.index(range.upperBound, offsetBy: 120, limitedBy: paragraph.endIndex) ?? paragraph.endIndex
        let lead: String = start > paragraph.startIndex ? "…" : ""
        let tail: String = end < paragraph.endIndex ? "…" : ""
        let snippet = lead + String(paragraph[start..<end]) + tail
        found.append(Hit(book: id, title: title, position: Position(chapter: c, paragraph: p, offset: 0), snippet: snippet))
        if found.count >= 40 { return found }
      }
    }
    return found
  }
}
