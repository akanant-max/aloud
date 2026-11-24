import AVFoundation

/// Renders the iPad's own voices to audio, so they play through the same pipeline as the
/// neural voices: render ahead, play gaplessly, change speed without a restart.
final class SystemEngine: NSObject {

  private let synthesizer = AVSpeechSynthesizer()
  private var busy = false

  override init() {
    super.init()
    synthesizer.usesApplicationAudioSession = true
    synthesizer.mixToTelephonyUplink = false
  }

  /// Renders one utterance into PCM buffers.
  @MainActor
  func render(_ text: String, voiceIdentifier: String?) async throws -> [AVAudioPCMBuffer] {
    while busy { try await Task.sleep(nanoseconds: 20_000_000) }
    busy = true
    defer { busy = false }

    let utterance = AVSpeechUtterance(string: text)
    if let voiceIdentifier, let voice = AVSpeechSynthesisVoice(identifier: voiceIdentifier) {
      utterance.voice = voice
    } else {
      utterance.voice = AVSpeechSynthesisVoice(language: AVSpeechSynthesisVoice.currentLanguageCode())
    }
    utterance.rate = AVSpeechUtteranceDefaultSpeechRate
    utterance.prefersAssistiveTechnologySettings = false

    return try await withCheckedThrowingContinuation { continuation in
      let state = RenderState(continuation)
      synthesizer.write(utterance) { buffer in
        guard let pcm = buffer as? AVAudioPCMBuffer else { return }
        if pcm.frameLength == 0 {
          state.finish()
        } else {
          if let copy = AudioTools.copy(pcm) { state.append(copy) }
        }
      }
      // Some voices never send the closing empty buffer; a stall ends the utterance.
      Task {
        var last = -1
        while !state.isFinished {
          try? await Task.sleep(nanoseconds: 1_500_000_000)
          let count = state.count
          if count == last { state.finish(); break }
          last = count
        }
      }
    }
  }

  private final class RenderState {
    private let lock = NSLock()
    private var buffers: [AVAudioPCMBuffer] = []
    private var continuation: CheckedContinuation<[AVAudioPCMBuffer], Error>?

    init(_ continuation: CheckedContinuation<[AVAudioPCMBuffer], Error>) { self.continuation = continuation }

    var isFinished: Bool { lock.lock(); defer { lock.unlock() }; return continuation == nil }
    var count: Int { lock.lock(); defer { lock.unlock() }; return buffers.count }

    func append(_ buffer: AVAudioPCMBuffer) {
      lock.lock(); defer { lock.unlock() }
      if continuation != nil { buffers.append(buffer) }
    }

    func finish() {
      lock.lock()
      let c = continuation
      continuation = nil
      let result = buffers
      lock.unlock()
      if result.isEmpty {
        c?.resume(throwing: ImportError("The iPad voice produced no sound. It may still be downloading in Settings."))
      } else {
        c?.resume(returning: result)
      }
    }
  }
}
