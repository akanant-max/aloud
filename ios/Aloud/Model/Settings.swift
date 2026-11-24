import Foundation
import SwiftUI

enum ThemeMode: String, CaseIterable, Identifiable {
  case system, light, dark
  var id: String { rawValue }
  var label: String { rawValue.capitalized }
  var scheme: ColorScheme? {
    switch self {
    case .system: return nil
    case .light: return .light
    case .dark: return .dark
    }
  }
}

enum AccentChoice: String, CaseIterable, Identifiable {
  case blue, lagoon, forest, sunset, berry, lavender
  var id: String { rawValue }
  var label: String { rawValue.capitalized }
  var color: Color {
    switch self {
    case .blue: return Color(red: 0.27, green: 0.45, blue: 0.85)
    case .lagoon: return Color(red: 0.0, green: 0.55, blue: 0.6)
    case .forest: return Color(red: 0.24, green: 0.55, blue: 0.3)
    case .sunset: return Color(red: 0.88, green: 0.42, blue: 0.2)
    case .berry: return Color(red: 0.75, green: 0.22, blue: 0.45)
    case .lavender: return Color(red: 0.52, green: 0.42, blue: 0.85)
    }
  }
}

enum LibraryLayout: String, CaseIterable, Identifiable {
  case grid, list
  var id: String { rawValue }
}

/// The app's settings, kept in UserDefaults.
@MainActor
final class Settings: ObservableObject {
  private let store = UserDefaults.standard

  @Published var theme: ThemeMode { didSet { store.set(theme.rawValue, forKey: "theme") } }
  @Published var pureBlack: Bool { didSet { store.set(pureBlack, forKey: "pureBlack") } }
  @Published var accent: AccentChoice { didSet { store.set(accent.rawValue, forKey: "accent") } }
  @Published var layout: LibraryLayout { didSet { store.set(layout.rawValue, forKey: "layout") } }
  @Published var sort: LibrarySort { didSet { store.set(sort.rawValue, forKey: "sort") } }
  @Published var collapsedShelves: Set<String> { didSet { store.set(Array(collapsedShelves), forKey: "collapsedShelves") } }

  /// The chosen voice's key; empty means the iPad's default voice.
  @Published var voiceKey: String { didSet { store.set(voiceKey, forKey: "voiceKey") } }
  @Published var hiddenVoices: Set<String> { didSet { store.set(Array(hiddenVoices), forKey: "hiddenVoices") } }
  @Published var showAllLanguages: Bool { didSet { store.set(showAllLanguages, forKey: "showAllLanguages") } }

  @Published var speed: Double { didSet { store.set(speed, forKey: "speed") } }
  /// After a long pause, start again from the beginning of the sentence.
  @Published var replayAfterPause: Bool { didSet { store.set(replayAfterPause, forKey: "replayAfterPause") } }
  /// How far the skip buttons on the lock screen and headphones jump, in seconds of listening.
  @Published var skipSeconds: Int { didSet { store.set(skipSeconds, forKey: "skipSeconds") } }
  @Published var readerTextSize: Double { didSet { store.set(readerTextSize, forKey: "readerTextSize") } }
  @Published var sleepMinutes: Int { didSet { store.set(sleepMinutes, forKey: "sleepMinutes") } }
  @Published var fadeOutSleep: Bool { didSet { store.set(fadeOutSleep, forKey: "fadeOutSleep") } }

  /// Learned listening pace at 1×, in words per second, and how much listening it rests on.
  @Published var learnedWordsPerSecond: Double { didSet { store.set(learnedWordsPerSecond, forKey: "wps") } }
  @Published var learnedSeconds: Double { didSet { store.set(learnedSeconds, forKey: "wpsSeconds") } }

  init() {
    theme = ThemeMode(rawValue: store.string(forKey: "theme") ?? "") ?? .system
    pureBlack = store.bool(forKey: "pureBlack")
    accent = AccentChoice(rawValue: store.string(forKey: "accent") ?? "") ?? .blue
    layout = LibraryLayout(rawValue: store.string(forKey: "layout") ?? "") ?? .grid
    sort = LibrarySort(rawValue: store.string(forKey: "sort") ?? "") ?? .recent
    collapsedShelves = Set(store.stringArray(forKey: "collapsedShelves") ?? [])
    voiceKey = store.string(forKey: "voiceKey") ?? ""
    hiddenVoices = Set(store.stringArray(forKey: "hiddenVoices") ?? [])
    showAllLanguages = store.bool(forKey: "showAllLanguages")
    speed = store.object(forKey: "speed") as? Double ?? 1.0
    replayAfterPause = store.object(forKey: "replayAfterPause") as? Bool ?? true
    skipSeconds = store.object(forKey: "skipSeconds") as? Int ?? 15
    readerTextSize = store.object(forKey: "readerTextSize") as? Double ?? 19
    sleepMinutes = store.object(forKey: "sleepMinutes") as? Int ?? 30
    fadeOutSleep = store.object(forKey: "fadeOutSleep") as? Bool ?? true
    learnedWordsPerSecond = store.object(forKey: "wps") as? Double ?? 2.6
    learnedSeconds = store.object(forKey: "wpsSeconds") as? Double ?? 0
  }

  /// Folds a stretch of listening into the learned pace.
  func learnPace(words: Int, seconds: Double, speed: Double) {
    guard words > 0, seconds > 0.5, speed > 0 else { return }
    let observed = Double(words) / seconds / speed
    guard observed > 0.8, observed < 6 else { return }
    let weight = min(seconds, 60) / (learnedSeconds + min(seconds, 60))
    learnedWordsPerSecond += (observed - learnedWordsPerSecond) * max(weight, 0.02)
    learnedSeconds = min(learnedSeconds + seconds, 36_000)
  }

  /// Listening time for some words at the current speed.
  func listeningTime(words: Int) -> TimeInterval {
    Double(words) / max(learnedWordsPerSecond * speed, 0.1)
  }
}

enum Format {
  static func duration(_ seconds: TimeInterval) -> String {
    let minutes = Int((seconds / 60).rounded())
    if minutes < 1 { return "under a minute" }
    if minutes < 60 { return "\(minutes) min" }
    let hours = minutes / 60, rest = minutes % 60
    return rest == 0 ? "\(hours) h" : "\(hours) h \(rest) min"
  }

  static func speed(_ value: Double) -> String {
    let text = String(format: "%.2f", value)
      .replacingOccurrences(of: #"0+$"#, with: "", options: .regularExpression)
      .replacingOccurrences(of: #"\.$"#, with: "", options: .regularExpression)
    return text + "×"
  }

  static func number(_ value: Int) -> String {
    NumberFormatter.localizedString(from: NSNumber(value: value), number: .decimal)
  }
}
