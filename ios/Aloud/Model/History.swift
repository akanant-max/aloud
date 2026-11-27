import Foundation
import SwiftUI

/// One remembered place in a book, and why it was remembered.
struct HistoryEntry: Codable, Equatable, Identifiable {
  var id = UUID()
  var time: Date
  var position: Position
  var chapterTitle: String
  var reason: String
  /// A few words from that spot, so it can be recognised.
  var snippet: String
  /// Recorded because the listener did something, which means they were awake for it.
  var awake: Bool

  static let pressedPlay = "Pressed play"
  static let paused = "Paused"
  static let openedApp = "Opened Aloud"
  static let beforeJump = "Before jumping"
  static let sleepTimer = "Sleep timer stopped it"

  var symbol: String {
    switch reason {
    case Self.pressedPlay: return "play.fill"
    case Self.paused: return "pause.fill"
    case Self.openedApp: return "arrow.up.forward.app"
    case Self.beforeJump: return "arrow.uturn.forward"
    case Self.sleepTimer: return "moon.zzz.fill"
    default: return "clock"
    }
  }

  var whenText: String {
    let clock = time.formatted(date: .omitted, time: .shortened)
    if Calendar.current.isDateInToday(time) { return "today, \(clock)" }
    if Calendar.current.isDateInYesterday(time) { return "yesterday, \(clock)" }
    return time.formatted(.dateTime.day().month(.abbreviated)) + ", \(clock)"
  }
}

/// Narration ran on well past where the listener was last awake.
struct PlaceSuggestion: Equatable {
  var bookID: UUID
  var entry: HistoryEntry
  var nowChapterTitle: String
}

/// Where each book was when the listener pressed play, paused, jumped or opened the app.
@MainActor
final class ListeningHistory: ObservableObject {

  @Published private(set) var books: [UUID: [HistoryEntry]] = [:]
  @Published var suggestion: PlaceSuggestion?

  private let file = Paths.support.appendingPathComponent("history.json")
  private var saveTask: Task<Void, Never>?

  static let maxPerBook = 40
  static let sameMoment: TimeInterval = 90
  /// How far narration must have run on, unattended, before offering the way back.
  static let driftParagraphs = 25
  static let driftTime: TimeInterval = 10 * 60

  init() {
    if let data = try? Data(contentsOf: file),
       let stored = try? JSONDecoder().decode([UUID: [HistoryEntry]].self, from: data) {
      books = stored
    }
  }

  func entries(_ bookID: UUID) -> [HistoryEntry] { books[bookID] ?? [] }

  func latestAwake(_ bookID: UUID) -> HistoryEntry? { books[bookID]?.first { $0.awake } }

  func record(_ bookID: UUID, _ entry: HistoryEntry) {
    var list = books[bookID] ?? []
    if let last = list.first,
       entry.time.timeIntervalSince(last.time) < Self.sameMoment,
       last.position.chapter == entry.position.chapter,
       abs(last.position.paragraph - entry.position.paragraph) <= 2 {
      // The same spot moments later says nothing new.
      if entry.awake && !last.awake { list[0].awake = true; books[bookID] = list; save() }
      return
    }
    list.insert(entry, at: 0)
    if list.count > Self.maxPerBook { list.removeLast(list.count - Self.maxPerBook) }
    books[bookID] = list
    save()
  }

  func forget(_ bookID: UUID) {
    books[bookID] = nil
    if suggestion?.bookID == bookID { suggestion = nil }
    save()
  }

  private func save() {
    saveTask?.cancel()
    let snapshot = books
    let file = self.file
    saveTask = Task.detached(priority: .utility) {
      try? await Task.sleep(nanoseconds: 300_000_000)
      if Task.isCancelled { return }
      if let data = try? JSONEncoder().encode(snapshot) { try? data.write(to: file, options: .atomic) }
    }
  }
}

/// Offered after opening the app when narration ran on well past the last thing the listener did.
struct PlaceSuggestionCard: View {
  @EnvironmentObject private var history: ListeningHistory
  @EnvironmentObject private var narrator: Narrator
  @EnvironmentObject private var settings: Settings

  var body: some View {
    if let suggestion = history.suggestion, narrator.bookID == suggestion.bookID {
      let entry = suggestion.entry
      VStack(alignment: .leading, spacing: 8) {
        Label("Dozed off?", systemImage: "moon.zzz.fill").font(.headline)
        Text("When you last used Aloud (\(entry.whenText)) you were in \(entry.chapterTitle). It has read on to \(suggestion.nowChapterTitle).")
          .font(.subheadline)
        if !entry.snippet.isEmpty {
          Text("“\(entry.snippet)”").font(.caption).foregroundStyle(.secondary).lineLimit(2)
        }
        HStack {
          Button("Go back there") {
            history.suggestion = nil
            narrator.jump(to: entry.position)
          }
          .buttonStyle(.borderedProminent)
          Button("I'm following") { history.suggestion = nil }
            .buttonStyle(.bordered)
        }
        .padding(.top, 4)
      }
      .padding(16)
      .frame(maxWidth: .infinity, alignment: .leading)
      .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Theme.tint(settings, opacity: 0.28)))
    }
  }
}

/// The places remembered for a book, newest first. Tapping one goes back to it.
struct HistoryList: View {
  @EnvironmentObject private var history: ListeningHistory
  let bookID: UUID
  var limit = 12
  let pick: (HistoryEntry) -> Void

  var body: some View {
    let entries = Array(history.entries(bookID).prefix(limit))
    if entries.isEmpty {
      Text("Places you press play, pause, jump from or open the app at are listed here, so you can find your way back after dozing off.")
        .font(.caption).foregroundStyle(.secondary)
    } else {
      VStack(spacing: 0) {
        ForEach(entries) { entry in
          Button { pick(entry) } label: {
            HStack(spacing: 14) {
              Image(systemName: entry.symbol).foregroundStyle(.tint).frame(width: 24)
              VStack(alignment: .leading, spacing: 2) {
                Text(entry.chapterTitle).lineLimit(1)
                if !entry.snippet.isEmpty {
                  Text(entry.snippet).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                Text("\(entry.reason) · \(entry.whenText)").font(.caption2).foregroundStyle(.secondary)
              }
              Spacer()
            }
            .padding(.vertical, 9)
            .contentShape(Rectangle())
          }
          .buttonStyle(.plain)
          Divider()
        }
      }
    }
  }
}
