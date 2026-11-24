import AVFoundation
import Foundation
import ZIPFoundation

enum VoiceKind: String { case system, piper, natural }

/// A voice that can read: one of the iPad's own, a downloaded Piper voice, or a natural voice.
struct VoiceOption: Identifiable, Hashable {
  var key: String
  var name: String
  var detail: String
  var kind: VoiceKind
  var language: String
  var systemIdentifier: String?
  var directory: URL?
  var speaker: Int = 0
  var british = false

  var id: String { key }
}

struct DownloadableVoice: Identifiable {
  var id: String
  var name: String
  var note: String
  var megabytes: Int
}

struct NaturalSpeaker {
  var id: String
  var sid: Int
  var name: String
  var note: String
}

/// The voices on this iPad and those that can be downloaded.
@MainActor
final class Voices: ObservableObject {

  static let mirror = "https://github.com/akanant-max/aloud/releases/download/ios-voices/"

  static let piperCatalogue: [DownloadableVoice] = [
    DownloadableVoice(id: "en_US-lessac-medium", name: "Lessac", note: "American · clear and even; the Windows default", megabytes: 64),
    DownloadableVoice(id: "en_US-lessac-high", name: "Lessac (high quality)", note: "American · the same voice, richer and slower to make", megabytes: 110),
    DownloadableVoice(id: "en_US-ryan-high", name: "Ryan", note: "American, male · warm", megabytes: 110),
    DownloadableVoice(id: "en_US-amy-medium", name: "Amy", note: "American, female · bright", megabytes: 64),
    DownloadableVoice(id: "en_US-hfc_female-medium", name: "Calm female", note: "American, female · calm", megabytes: 64),
    DownloadableVoice(id: "en_GB-alba-medium", name: "Alba", note: "Scottish, female", megabytes: 64),
    DownloadableVoice(id: "en_GB-cori-high", name: "Cori", note: "British, female", megabytes: 110),
    DownloadableVoice(id: "en_GB-northern_english_male-medium", name: "Northern English", note: "British, male", megabytes: 64),
  ]

  static let naturalMegabytes = 130

  static let naturalSpeakers: [NaturalSpeaker] = [
    NaturalSpeaker(id: "af_heart", sid: 3, name: "Heart", note: "American, female · closest to Google's voice"),
    NaturalSpeaker(id: "af_sarah", sid: 9, name: "Sarah", note: "American, female · bright"),
    NaturalSpeaker(id: "af_bella", sid: 2, name: "Bella", note: "American, female · warm"),
    NaturalSpeaker(id: "af_nicole", sid: 6, name: "Nicole", note: "American, female · soft, close"),
    NaturalSpeaker(id: "af_aoede", sid: 1, name: "Aoede", note: "American, female · smooth"),
    NaturalSpeaker(id: "af_kore", sid: 5, name: "Kore", note: "American, female · even"),
    NaturalSpeaker(id: "am_michael", sid: 16, name: "Michael", note: "American, male · steady"),
    NaturalSpeaker(id: "am_fenrir", sid: 14, name: "Fenrir", note: "American, male · deep"),
    NaturalSpeaker(id: "am_puck", sid: 18, name: "Puck", note: "American, male · lively"),
    NaturalSpeaker(id: "am_adam", sid: 11, name: "Adam", note: "American, male"),
    NaturalSpeaker(id: "bf_emma", sid: 21, name: "Emma", note: "British, female"),
    NaturalSpeaker(id: "bf_isabella", sid: 22, name: "Isabella", note: "British, female · soft"),
    NaturalSpeaker(id: "bm_george", sid: 26, name: "George", note: "British, male"),
    NaturalSpeaker(id: "bm_fable", sid: 25, name: "Fable", note: "British, male · storyteller"),
  ]

  /// Download progress by key ("natural" or a Piper voice's id); 0 means waiting its turn.
  @Published private(set) var downloads: [String: Double] = [:]
  @Published var message: String?
  @Published private(set) var revision = 0

  private var downloadQueue: [(key: String, run: () async throws -> Void)] = []
  private var downloading = false

  static var piperRoot: URL { Paths.voices.appendingPathComponent("piper", isDirectory: true) }
  static var naturalDir: URL { Paths.voices.appendingPathComponent("natural", isDirectory: true) }

  // MARK: Listing

  func system(allLanguages: Bool) -> [VoiceOption] {
    let english = AVSpeechSynthesisVoice.speechVoices().filter { voice in
      allLanguages || voice.language.hasPrefix("en")
    }
    // Novelty voices make poor narrators.
    let novelty: Set<String> = ["Albert", "Bad News", "Bahh", "Bells", "Boing", "Bubbles", "Cellos", "Good News",
                                "Jester", "Organ", "Superstar", "Trinoids", "Whisper", "Wobble", "Zarvox", "Fred", "Junior",
                                "Kathy", "Ralph"]
    return english
      .filter { !novelty.contains($0.name) }
      .sorted { a, b in
        if a.quality != b.quality { return a.quality.rawValue > b.quality.rawValue }
        if a.language != b.language { return a.language < b.language }
        return a.name < b.name
      }
      .map { voice in
        let quality: String
        switch voice.quality {
        case .premium: quality = "Premium"
        case .enhanced: quality = "Enhanced"
        default: quality = "Standard"
        }
        let language = Locale.current.localizedString(forIdentifier: voice.language) ?? voice.language
        return VoiceOption(key: "system:\(voice.identifier)", name: voice.name, detail: "\(language) · \(quality)",
                           kind: .system, language: voice.language, systemIdentifier: voice.identifier)
      }
  }

  func piperInstalled() -> [VoiceOption] {
    Self.piperCatalogue.compactMap { entry in
      let dir = Self.piperRoot.appendingPathComponent(entry.id, isDirectory: true)
      guard FileManager.default.fileExists(atPath: dir.appendingPathComponent("tokens.txt").path) else { return nil }
      return VoiceOption(key: "piper:\(entry.id)", name: entry.name, detail: entry.note, kind: .piper,
                         language: entry.id.hasPrefix("en_GB") ? "en-GB" : "en-US", directory: dir)
    }
  }

  var naturalInstalled: Bool {
    ["model.int8.onnx", "voices.bin", "tokens.txt", "lexicon-us-en.txt"].allSatisfy {
      FileManager.default.fileExists(atPath: Self.naturalDir.appendingPathComponent($0).path)
    }
  }

  func natural() -> [VoiceOption] {
    guard naturalInstalled else { return [] }
    return Self.naturalSpeakers.map { s in
      VoiceOption(key: "natural:\(s.id)", name: s.name, detail: s.note, kind: .natural,
                  language: s.id.hasPrefix("b") ? "en-GB" : "en-US", directory: Self.naturalDir,
                  speaker: s.sid, british: s.id.hasPrefix("b"))
    }
  }

  func all(allLanguages: Bool = true) -> [VoiceOption] {
    natural() + piperInstalled() + system(allLanguages: allLanguages)
  }

  /// The voice to read with: the chosen one if it is still here, else Heart if the natural
  /// voices are installed, else the iPad's best English voice.
  func resolve(_ key: String) -> VoiceOption? {
    let everything = all()
    if let chosen = everything.first(where: { $0.key == key }) { return chosen }
    return natural().first ?? system(allLanguages: false).first ?? everything.first
  }

  // MARK: Downloads

  func downloadPiper(_ voice: DownloadableVoice) {
    let key = voice.id
    enqueue(key) {
      let target = Self.piperRoot.appendingPathComponent(voice.id, isDirectory: true)
      try await self.fetchAndUnzip(URL(string: Self.mirror + "piper-\(voice.id).zip")!, into: target, key: key)
      self.message = "\(voice.name) is ready"
    }
  }

  func downloadNatural(onReady: @escaping () -> Void) {
    enqueue("natural") {
      try await self.fetchAndUnzip(URL(string: Self.mirror + "natural-voices.zip")!, into: Self.naturalDir, key: "natural")
      self.message = "The natural voices are ready. Heart is reading now; try the others with their play buttons."
      onReady()
    }
  }

  func removePiper(_ id: String) {
    try? FileManager.default.removeItem(at: Self.piperRoot.appendingPathComponent(id, isDirectory: true))
    revision += 1
  }

  func removeNatural() {
    try? FileManager.default.removeItem(at: Self.naturalDir)
    revision += 1
  }

  private func enqueue(_ key: String, _ run: @escaping () async throws -> Void) {
    guard downloads[key] == nil else { return }
    downloads[key] = 0
    downloadQueue.append((key, run))
    if !downloading { Task { await drain() } }
  }

  private func drain() async {
    downloading = true
    while !downloadQueue.isEmpty {
      let next = downloadQueue.removeFirst()
      downloads[next.key] = 0.001
      do {
        try await next.run()
      } catch {
        message = "Download failed: \(error.localizedDescription)"
      }
      downloads[next.key] = nil
      revision += 1
    }
    downloading = false
  }

  private func fetchAndUnzip(_ url: URL, into target: URL, key: String) async throws {
    var lastError: Error?
    var file: URL?
    for attempt in 0..<4 {
      if attempt > 0 { try await Task.sleep(nanoseconds: UInt64(2_000_000_000 * attempt * attempt)) }
      do {
        file = try await Downloader.fetch(url) { [weak self] fraction in
          Task { @MainActor in
            if self?.downloads[key] != nil { self?.downloads[key] = max(0.001, fraction * 0.85) }
          }
        }
        break
      } catch let error as Downloader.Failure where error.status == 404 {
        throw ImportError("This voice is not available yet. Try again later.")
      } catch {
        lastError = error
      }
    }
    guard let file else { throw lastError ?? ImportError("The download did not finish.") }
    defer { try? FileManager.default.removeItem(at: file) }

    downloads[key] = 0.9
    let staging = target.deletingLastPathComponent().appendingPathComponent(target.lastPathComponent + ".unpacking", isDirectory: true)
    try await Task.detached(priority: .userInitiated) {
      let fm = FileManager.default
      try? fm.removeItem(at: staging)
      try fm.createDirectory(at: staging, withIntermediateDirectories: true)
      try fm.unzipItem(at: file, to: staging)
      try? fm.removeItem(at: target)
      try fm.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
      try fm.moveItem(at: staging, to: target)
    }.value
  }
}

/// Downloads one file to a temporary place, reporting progress.
enum Downloader {
  struct Failure: LocalizedError {
    var status: Int
    var errorDescription: String? { "the server answered \(status)" }
  }

  static func fetch(_ url: URL, progress: @escaping (Double) -> Void) async throws -> URL {
    let delegate = ProgressDelegate(progress)
    let session = URLSession(configuration: .default, delegate: delegate, delegateQueue: nil)
    defer { session.finishTasksAndInvalidate() }
    return try await withCheckedThrowingContinuation { continuation in
      delegate.continuation = continuation
      session.downloadTask(with: url).resume()
    }
  }

  private final class ProgressDelegate: NSObject, URLSessionDownloadDelegate {
    let progress: (Double) -> Void
    var continuation: CheckedContinuation<URL, Error>?

    init(_ progress: @escaping (Double) -> Void) { self.progress = progress }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
      if totalBytesExpectedToWrite > 0 { progress(Double(totalBytesWritten) / Double(totalBytesExpectedToWrite)) }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
      let status = (downloadTask.response as? HTTPURLResponse)?.statusCode ?? 200
      guard (200..<300).contains(status) else {
        continuation?.resume(throwing: Failure(status: status))
        continuation = nil
        return
      }
      // The file is deleted when this returns, so it is moved somewhere safe first.
      let kept = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".zip")
      do {
        try FileManager.default.moveItem(at: location, to: kept)
        continuation?.resume(returning: kept)
      } catch {
        continuation?.resume(throwing: error)
      }
      continuation = nil
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
      if let error {
        continuation?.resume(throwing: error)
        continuation = nil
      }
    }
  }
}
