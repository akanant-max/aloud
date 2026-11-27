import AVFoundation
import Combine
import MediaPlayer
import UIKit

/// Reads a book aloud: renders a few stretches ahead with the chosen voice, plays them gaplessly,
/// keeps the place, and answers the lock screen, headphones and Control Centre.
@MainActor
final class Narrator: ObservableObject {

  @Published private(set) var bookID: UUID?
  @Published private(set) var current: SpeechChunk?
  @Published private(set) var isPlaying = false
  @Published private(set) var isPreparing = false
  @Published var error: String?
  @Published private(set) var sleepRemaining: TimeInterval?
  @Published private(set) var sleepAtChapterEnd = false

  private let library: Library
  private let settings: Settings
  private let voices: Voices
  private let history: ListeningHistory

  private let engine = AVAudioEngine()
  private let player = AVAudioPlayerNode()
  private let previewPlayer = AVAudioPlayerNode()
  private let timePitch = AVAudioUnitTimePitch()
  private let sherpa = SherpaEngine()
  private let system = SystemEngine()

  private var generation = 0
  private var producer: Task<Void, Never>?
  private var producerDone = false
  /// Stretches handed to the player and not yet heard, in order.
  private var queued: [(chunk: SpeechChunk, seconds: Double)] = []
  private var content: BookContent?
  private var voice: VoiceOption?
  private var pausedAt: Date?
  private var sleepTimer: Timer?
  private var subscriptions = Set<AnyCancellable>()

  private static let renderAhead = 3

  init(library: Library, settings: Settings, voices: Voices, history: ListeningHistory) {
    self.history = history
    self.library = library
    self.settings = settings
    self.voices = voices

    engine.attach(player)
    engine.attach(timePitch)
    engine.connect(player, to: timePitch, format: AudioTools.format)
    engine.connect(timePitch, to: engine.mainMixerNode, format: AudioTools.format)
    engine.attach(previewPlayer)
    engine.connect(previewPlayer, to: engine.mainMixerNode, format: AudioTools.format)
    timePitch.rate = Float(settings.speed)

    settings.$speed
      .removeDuplicates()
      .sink { [weak self] speed in
        MainActor.assumeIsolated {
          self?.timePitch.rate = Float(max(0.5, min(4, speed)))
          self?.updateNowPlaying()
        }
      }
      .store(in: &subscriptions)
    settings.$voiceKey
      .dropFirst()
      .removeDuplicates()
      .sink { [weak self] _ in
        Task { @MainActor in self?.voiceChanged() }
      }
      .store(in: &subscriptions)

    observeSession()
    setUpRemoteCommands()
  }

  var book: Book? { bookID.flatMap(library.book) }

  // MARK: Playback

  /// Plays a book from where it was left, or from a given place.
  func play(_ id: UUID, at position: Position? = nil) {
    guard let book = library.book(id) else { return }
    guard let content = library.content(for: id) else {
      error = "This book's text could not be found. Removing it and adding it again should fix it."
      return
    }
    if bookID != id { save() }
    bookID = id
    self.content = content
    var place = position ?? book.position
    if book.completed && position == nil { place = .start }
    library.update(id) {
      $0.lastPlayed = Date()
      if $0.completed && position == nil { $0.completed = false; $0.position = .start }
    }
    start(from: place)
    note(HistoryEntry.pressedPlay)
  }

  func togglePlayPause() {
    if isPlaying { pause() } else { resume() }
  }

  /// Pauses. `automatic` is for pauses nobody asked for just then, like the sleep timer's.
  func pause(automatic: Bool = false) {
    guard isPlaying else { return }
    note(automatic ? HistoryEntry.sleepTimer : HistoryEntry.paused, awake: !automatic)
    player.pause()
    isPlaying = false
    pausedAt = Date()
    save()
    updateNowPlaying()
  }

  func resume() {
    guard let bookID else { return }
    if isPlaying { return }
    note(HistoryEntry.pressedPlay)
    let longPause = pausedAt.map { Date().timeIntervalSince($0) > 30 } ?? true
    if queued.isEmpty || (settings.replayAfterPause && longPause) || !engine.isRunning {
      // Start the sentence again, which also rebuilds anything the system tore down.
      if let current {
        start(from: Position(chapter: current.chapter, paragraph: current.paragraph, offset: current.start))
      } else {
        play(bookID)
      }
      return
    }
    activateSession()
    player.play()
    isPlaying = true
    pausedAt = nil
    updateNowPlaying()
  }

  func stop() {
    save()
    halt()
    isPlaying = false
    isPreparing = false
    updateNowPlaying()
  }

  /// Jumps to a paragraph, keeping playing or paused as it was.
  func jump(to position: Position) {
    guard bookID != nil else { return }
    // Remember where this jump left from, so it can be undone from the history.
    if let from = currentPosition, from.chapter != position.chapter || abs(from.paragraph - position.paragraph) > 2 {
      note(HistoryEntry.beforeJump)
    }
    let wasPlaying = isPlaying || isPreparing
    if wasPlaying {
      start(from: position)
    } else {
      halt()
      setPosition(position)
      save()
    }
  }

  func nextChapter() {
    guard let position = currentPosition, let content else { return }
    let next = position.chapter + 1
    guard next < content.chapters.count else { return }
    jump(to: Position(chapter: next, paragraph: 0, offset: 0))
  }

  func previousChapter() {
    guard let position = currentPosition else { return }
    // Within the first paragraphs, go to the chapter before; otherwise to this one's start.
    let target = position.paragraph <= 1 && position.offset == 0 ? max(0, position.chapter - 1) : position.chapter
    jump(to: Position(chapter: target, paragraph: 0, offset: 0))
  }

  func nextParagraph() { move(paragraphs: 1) }
  func previousParagraph() { move(paragraphs: current.map { $0.start > 0 ? 0 : -1 } ?? -1) }

  /// Skips about this many seconds of listening forward or back, a paragraph at a time.
  func skip(seconds: Double) {
    guard let position = currentPosition, let content else { return }
    var words = Int(abs(seconds) * settings.learnedWordsPerSecond)
    var chapter = position.chapter, paragraph = position.paragraph
    if seconds > 0 {
      while words > 0 {
        words -= Words.count(content.chapters[chapter].paragraphs[paragraph])
        if paragraph + 1 < content.chapters[chapter].paragraphs.count {
          paragraph += 1
        } else if chapter + 1 < content.chapters.count {
          chapter += 1; paragraph = 0
        } else {
          break
        }
      }
    } else {
      if position.offset > 0 { words -= Words.count((content.chapters[chapter].paragraphs[paragraph] as NSString).substring(to: min(position.offset, (content.chapters[chapter].paragraphs[paragraph] as NSString).length))) }
      while words > 0 {
        if paragraph > 0 {
          paragraph -= 1
        } else if chapter > 0 {
          chapter -= 1; paragraph = max(0, content.chapters[chapter].paragraphs.count - 1)
        } else {
          break
        }
        words -= Words.count(content.chapters[chapter].paragraphs[paragraph])
      }
    }
    jump(to: Position(chapter: chapter, paragraph: paragraph, offset: 0))
  }

  private func move(paragraphs delta: Int) {
    guard let position = currentPosition, let content else { return }
    var chapter = position.chapter, paragraph = position.paragraph + delta
    if delta == 0 { jump(to: Position(chapter: chapter, paragraph: paragraph, offset: 0)); return }
    if paragraph >= content.chapters[chapter].paragraphs.count {
      guard chapter + 1 < content.chapters.count else { return }
      chapter += 1; paragraph = 0
    } else if paragraph < 0 {
      guard chapter > 0 else { paragraph = 0; jump(to: Position(chapter: 0, paragraph: 0, offset: 0)); return }
      chapter -= 1; paragraph = max(0, content.chapters[chapter].paragraphs.count - 1)
    }
    jump(to: Position(chapter: chapter, paragraph: paragraph, offset: 0))
  }

  /// Where listening is: the stretch being heard, or the book's saved place.
  var currentPosition: Position? {
    if let current { return Position(chapter: current.chapter, paragraph: current.paragraph, offset: current.start) }
    return book?.position
  }

  // MARK: Trying a voice

  @Published private(set) var previewing: String?

  /// Says a sentence in a voice, pausing the book first.
  func preview(_ voice: VoiceOption) {
    guard previewing == nil else { return }
    pause()
    previewing = voice.key
    Task {
      defer { previewing = nil }
      do {
        let buffer = try await render("This is how this voice will read your books.", voice: voice)
        activateSession()
        if !engine.isRunning { try engine.start() }
        previewPlayer.stop()
        previewPlayer.play()
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
          previewPlayer.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack) { _ in done.resume() }
        }
      } catch {
        voices.message = "That voice could not be played: \(error.localizedDescription)"
      }
    }
  }

  // MARK: Sleep timer

  func setSleepTimer(minutes: Int?) {
    sleepTimer?.invalidate()
    sleepTimer = nil
    sleepAtChapterEnd = false
    engine.mainMixerNode.outputVolume = 1
    guard let minutes, minutes > 0 else { sleepRemaining = nil; return }
    sleepRemaining = TimeInterval(minutes * 60)
    sleepTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
      Task { @MainActor in self?.tickSleep() }
    }
  }

  func sleepAtEndOfChapter() {
    setSleepTimer(minutes: nil)
    sleepAtChapterEnd = true
  }

  private func tickSleep() {
    guard var remaining = sleepRemaining else { return }
    guard isPlaying else { return }
    remaining -= 1
    sleepRemaining = remaining
    if settings.fadeOutSleep, remaining < 10 {
      engine.mainMixerNode.outputVolume = Float(max(0, remaining) / 10)
    }
    if remaining <= 0 {
      pause(automatic: true)
      setSleepTimer(minutes: nil)
    }
  }

  // MARK: The pipeline

  private func start(from position: Position) {
    halt()
    guard let content, !content.chapters.isEmpty else { return }
    guard let voice = voices.resolve(settings.voiceKey) else {
      error = "No voice is available. Choose one in Settings."
      return
    }
    self.voice = voice
    error = nil
    setPosition(position)
    activateSession()
    do {
      if !engine.isRunning { try engine.start() }
    } catch {
      self.error = "Sound could not start: \(error.localizedDescription)"
      return
    }
    player.play()
    isPlaying = true
    isPreparing = true
    pausedAt = nil
    producerDone = false
    let gen = generation
    producer = Task { [weak self] in await self?.produce(gen, content: content, from: position, voice: voice) }
    updateNowPlaying()
  }

  /// Stops everything in flight. Audio already rendered for the old place is thrown away.
  private func halt() {
    generation += 1
    producer?.cancel()
    producer = nil
    player.stop()
    queued.removeAll()
  }

  private func produce(_ gen: Int, content: BookContent, from position: Position, voice: VoiceOption) async {
    var chapter = max(0, min(position.chapter, content.chapters.count - 1))
    var paragraph = max(0, position.paragraph)
    var offset = max(0, position.offset)
    var first = true

    while gen == generation, chapter < content.chapters.count {
      let paragraphs = content.chapters[chapter].paragraphs
      if paragraph >= paragraphs.count {
        chapter += 1; paragraph = 0; offset = 0
        continue
      }
      let chunks = Chunker.chunks(chapter: chapter, paragraph: paragraph, text: paragraphs[paragraph], from: offset,
                                  limit: first ? Chunker.firstChunkChars : Chunker.maxChars)
      for chunk in chunks {
        // Keep only a few stretches ahead of the listener.
        while gen == generation, queued.count >= Self.renderAhead {
          try? await Task.sleep(nanoseconds: 80_000_000)
        }
        guard gen == generation else { return }
        do {
          let buffer = try await render(chunk.text, voice: voice)
          guard gen == generation else { return }
          schedule(buffer, chunk: chunk, gen: gen)
          first = false
        } catch {
          guard gen == generation else { return }
          self.error = "The voice failed: \(error.localizedDescription)"
          isPlaying = false
          isPreparing = false
          player.stop()
          updateNowPlaying()
          return
        }
      }
      paragraph += 1
      offset = 0
    }
    if gen == generation {
      producerDone = true
      if queued.isEmpty { finishedBook() }
    }
  }

  private func render(_ text: String, voice: VoiceOption) async throws -> AVAudioPCMBuffer {
    switch voice.kind {
    case .system:
      let buffers = try await system.render(text, voiceIdentifier: voice.systemIdentifier)
      let converted = await Task.detached(priority: .userInitiated) { AudioTools.convert(buffers) }.value
      guard let converted else { throw ImportError("The iPad voice's audio could not be used.") }
      return converted
    case .piper, .natural:
      guard let directory = voice.directory else { throw ImportError("The voice is missing.") }
      let model = SherpaEngine.Model(
        kind: voice.kind == .piper ? .piper : .kokoro(lexicon: voice.british ? "lexicon-gb-en.txt" : "lexicon-us-en.txt"),
        directory: directory)
      let audio = try await sherpa.generate(text, model: model, speaker: voice.speaker)
      let buffer = await Task.detached(priority: .userInitiated) { AudioTools.buffer(samples: audio.samples, rate: audio.rate) }.value
      guard let buffer else { throw ImportError("The voice's audio could not be used.") }
      return buffer
    }
  }

  private func schedule(_ buffer: AVAudioPCMBuffer, chunk: SpeechChunk, gen: Int) {
    let seconds = Double(buffer.frameLength) / buffer.format.sampleRate
    queued.append((chunk, seconds))
    if queued.count == 1 {
      current = chunk
      isPreparing = false
      updateNowPlaying()
    }
    player.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack) { [weak self] _ in
      Task { @MainActor in self?.heard(chunk, gen: gen) }
    }
  }

  /// A stretch has been heard in full.
  private func heard(_ chunk: SpeechChunk, gen: Int) {
    guard gen == generation, let first = queued.first, first.chunk == chunk else { return }
    queued.removeFirst()
    settings.learnPace(words: chunk.words, seconds: first.seconds, speed: 1)

    if let next = queued.first?.chunk {
      if sleepAtChapterEnd, next.chapter != chunk.chapter {
        current = next
        sleepAtChapterEnd = false
        pause(automatic: true)
        return
      }
      current = next
      updateNowPlaying()
    } else if producerDone {
      finishedBook()
      return
    } else {
      // The voice is behind; the next stretch is still being made.
      current = SpeechChunk(chapter: chunk.chapter, paragraph: chunk.paragraph, start: chunk.end, end: chunk.end, text: "")
      isPreparing = isPlaying
    }
    save()
  }

  private func finishedBook() {
    guard let bookID else { return }
    library.update(bookID) {
      $0.completed = true
      $0.position = .start
    }
    halt()
    current = nil
    isPlaying = false
    isPreparing = false
    sleepAtChapterEnd = false
    updateNowPlaying()
  }

  private func setPosition(_ position: Position) {
    current = SpeechChunk(chapter: position.chapter, paragraph: position.paragraph, start: position.offset, end: position.offset, text: "")
  }

  private func voiceChanged() {
    sherpa.unload()
    if isPlaying || isPreparing, let position = currentPosition {
      start(from: position)
    }
  }

  // MARK: Listening history

  /// The app has come to the front. If narration ran on a long way since the listener last did
  /// anything, they may have fallen asleep to it: offer the way back.
  func noteAppOpened() {
    guard let bookID, let book, let now = currentPosition else { return }
    if let last = history.latestAwake(bookID),
       Date().timeIntervalSince(last.time) >= ListeningHistory.driftTime,
       (now.chapter, now.paragraph) > (last.position.chapter, last.position.paragraph),
       now.chapter != last.position.chapter || now.paragraph - last.position.paragraph >= ListeningHistory.driftParagraphs {
      history.suggestion = PlaceSuggestion(bookID: bookID, entry: last, nowChapterTitle: book.chapterTitle(now.chapter))
    }
    note(HistoryEntry.openedApp)
  }

  private func note(_ reason: String, awake: Bool = true) {
    guard let bookID, let book, let at = currentPosition else { return }
    var snippet = ""
    if let content, at.chapter < content.chapters.count, at.paragraph < content.chapters[at.chapter].paragraphs.count {
      let text = content.chapters[at.chapter].paragraphs[at.paragraph] as NSString
      let rest = text.substring(from: min(at.offset, text.length)).trimmingCharacters(in: .whitespaces)
      if rest.count <= 90 {
        snippet = rest
      } else {
        let cut = String(rest.prefix(90))
        snippet = (cut.range(of: " ", options: .backwards).map { String(cut[..<$0.lowerBound]) } ?? cut) + "…"
      }
    }
    history.record(bookID, HistoryEntry(time: Date(), position: at, chapterTitle: book.chapterTitle(at.chapter),
                                        reason: reason, snippet: snippet, awake: awake))
  }

  /// Keeps the place in the library.
  func save() {
    guard let bookID, let position = currentPosition else { return }
    library.update(bookID) { $0.position = position }
  }

  // MARK: Audio session

  private func activateSession() {
    let session = AVAudioSession.sharedInstance()
    try? session.setCategory(.playback, mode: .spokenAudio, policy: .longFormAudio)
    try? session.setActive(true)
  }

  private func observeSession() {
    let center = NotificationCenter.default
    center.publisher(for: AVAudioSession.interruptionNotification)
      .receive(on: DispatchQueue.main)
      .sink { [weak self] note in MainActor.assumeIsolated {
        guard let self, let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
        if type == .began {
          if self.isPlaying {
            self.player.pause()
            self.isPlaying = false
            self.pausedAt = Date()
            self.updateNowPlaying()
          }
        } else if let optionsRaw = note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt,
                  AVAudioSession.InterruptionOptions(rawValue: optionsRaw).contains(.shouldResume) {
          self.resume()
        }
      } }
      .store(in: &subscriptions)

    center.publisher(for: AVAudioSession.routeChangeNotification)
      .receive(on: DispatchQueue.main)
      .sink { [weak self] note in MainActor.assumeIsolated {
        guard let raw = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
              AVAudioSession.RouteChangeReason(rawValue: raw) == .oldDeviceUnavailable else { return }
        // Headphones unplugged or disconnected: stop talking out loud.
        self?.pause(automatic: true)
      } }
      .store(in: &subscriptions)

    center.publisher(for: .AVAudioEngineConfigurationChange, object: engine)
      .receive(on: DispatchQueue.main)
      .sink { [weak self] _ in MainActor.assumeIsolated {
        guard let self, self.isPlaying, let position = self.currentPosition else { return }
        self.start(from: position)
      } }
      .store(in: &subscriptions)
  }

  // MARK: Lock screen and headphones

  private func setUpRemoteCommands() {
    let commands = MPRemoteCommandCenter.shared()
    commands.playCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { () -> MPRemoteCommandHandlerStatus in
      self?.resume(); return .success
    } }
    commands.pauseCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { () -> MPRemoteCommandHandlerStatus in
      self?.pause(); return .success
    } }
    commands.togglePlayPauseCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { () -> MPRemoteCommandHandlerStatus in
      self?.togglePlayPause(); return .success
    } }
    commands.nextTrackCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { () -> MPRemoteCommandHandlerStatus in
      self?.nextChapter(); return .success
    } }
    commands.previousTrackCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { () -> MPRemoteCommandHandlerStatus in
      self?.previousChapter(); return .success
    } }
    commands.skipForwardCommand.preferredIntervals = [NSNumber(value: settings.skipSeconds)]
    commands.skipBackwardCommand.preferredIntervals = [NSNumber(value: settings.skipSeconds)]
    commands.skipForwardCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { () -> MPRemoteCommandHandlerStatus in
      guard let self else { return .commandFailed }
      self.skip(seconds: Double(self.settings.skipSeconds)); return .success
    } }
    commands.skipBackwardCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { () -> MPRemoteCommandHandlerStatus in
      guard let self else { return .commandFailed }
      self.skip(seconds: -Double(self.settings.skipSeconds)); return .success
    } }
    commands.changePlaybackRateCommand.supportedPlaybackRates = [0.75, 1, 1.25, 1.5, 1.75, 2, 2.5, 3]
    commands.changePlaybackRateCommand.addTarget { [weak self] event in MainActor.assumeIsolated { () -> MPRemoteCommandHandlerStatus in
      guard let self, let event = event as? MPChangePlaybackRateCommandEvent else { return .commandFailed }
      self.settings.speed = Double(event.playbackRate); return .success
    } }
  }

  func updateNowPlaying() {
    let center = MPNowPlayingInfoCenter.default()
    guard let book else {
      center.nowPlayingInfo = nil
      return
    }
    let chapter = current?.chapter ?? book.position.chapter
    var info: [String: Any] = [
      MPMediaItemPropertyTitle: book.title,
      MPMediaItemPropertyArtist: book.chapterTitle(chapter),
      MPMediaItemPropertyAlbumTitle: book.author ?? "Aloud",
      MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? settings.speed : 0.0,
      MPNowPlayingInfoPropertyDefaultPlaybackRate: settings.speed,
      MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
    ]
    // The chapter as a timeline, in estimated listening time.
    if let content, chapter < content.chapters.count {
      let paragraphs = content.chapters[chapter].paragraphs
      let paragraph = min(current?.paragraph ?? 0, paragraphs.count)
      let wps = max(settings.learnedWordsPerSecond, 0.5)
      let before = paragraphs[..<paragraph].reduce(0) { $0 + Words.count($1) }
      let total = paragraphs.reduce(0) { $0 + Words.count($1) }
      info[MPMediaItemPropertyPlaybackDuration] = Double(total) / wps
      info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = Double(before) / wps
    }
    if let image = library.cover(for: book.id) {
      info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
    }
    center.nowPlayingInfo = info
    center.playbackState = isPlaying ? .playing : .paused
  }
}
