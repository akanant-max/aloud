import SwiftUI

/// A book's cover, or a coloured card with its title when it has none.
struct CoverView: View {
  @EnvironmentObject private var library: Library
  let book: Book
  var cornerRadius: CGFloat = 12

  var body: some View {
    ZStack {
      if book.hasCover, let image = cachedImage {
        Image(uiImage: image).resizable().scaledToFill()
      } else {
        LinearGradient(colors: placeholderColors, startPoint: .topLeading, endPoint: .bottomTrailing)
        VStack(spacing: 6) {
          Text(book.title)
            .font(.system(.headline, design: .serif))
            .multilineTextAlignment(.center)
            .lineLimit(4)
          if let author = book.author {
            Text(author).font(.caption).opacity(0.8).lineLimit(2)
          }
        }
        .foregroundStyle(.white)
        .padding(10)
      }
    }
    .aspectRatio(2.0 / 3.0, contentMode: .fit)
    .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
    .overlay(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous).strokeBorder(.primary.opacity(0.08)))
    .id("\(book.id)-\(library.coverVersion)")
  }

  private var cachedImage: UIImage? { CoverCache.shared.image(book.id, version: library.coverVersion) { library.cover(for: book.id) } }

  private var placeholderColors: [Color] {
    let hue = Double(book.title.unicodeScalars.reduce(0) { ($0 &* 31 &+ Int($1.value)) % 360 }) / 360
    return [Color(hue: hue, saturation: 0.45, brightness: 0.55), Color(hue: (hue + 0.08).truncatingRemainder(dividingBy: 1), saturation: 0.55, brightness: 0.35)]
  }
}

final class CoverCache {
  static let shared = CoverCache()
  private let cache = NSCache<NSString, UIImage>()

  func image(_ id: UUID, version: Int, load: () -> UIImage?) -> UIImage? {
    let key = "\(id.uuidString)-\(version)" as NSString
    if let hit = cache.object(forKey: key) { return hit }
    guard let image = load() else { return nil }
    cache.setObject(image, forKey: key)
    return image
  }
}

struct ProgressBar: View {
  let value: Double
  var height: CGFloat = 5

  var body: some View {
    GeometryReader { geometry in
      ZStack(alignment: .leading) {
        Capsule().fill(.primary.opacity(0.12))
        Capsule().fill(.tint).frame(width: max(height, geometry.size.width * min(1, max(0, value))))
      }
    }
    .frame(height: height)
  }
}

struct EmptyLibrary: View {
  let add: () -> Void

  var body: some View {
    VStack(spacing: 16) {
      Image(systemName: "books.vertical")
        .font(.system(size: 56, weight: .light))
        .foregroundStyle(.tint)
      Text("Your library is empty").font(.title2.weight(.semibold))
      Text("Add EPUB, PDF, TXT, Kindle (MOBI, AZW3), FictionBook, Word or web-page files. You can also drop books into the “Add books here” folder for Aloud in the Files app.")
        .multilineTextAlignment(.center)
        .foregroundStyle(.secondary)
        .frame(maxWidth: 460)
      Button(action: add) {
        Label("Add books", systemImage: "plus")
          .padding(.horizontal, 18).padding(.vertical, 6)
      }
      .buttonStyle(.borderedProminent)
    }
    .padding(40)
    .frame(maxWidth: .infinity, minHeight: 420)
  }
}

extension Book {
  /// One line about where listening stands.
  @MainActor
  func statusLine(settings: Settings) -> String {
    if completed { return "Finished" }
    if !started {
      return "\(Format.duration(settings.listeningTime(words: wordCount))) to listen"
    }
    let left = Int(Double(wordCount) * (1 - progress()))
    return "\(Int(progress() * 100))% · \(Format.duration(settings.listeningTime(words: left))) left"
  }
}
