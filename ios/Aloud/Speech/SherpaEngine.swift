import Foundation
import SherpaOnnxC

/// Runs neural voices (Piper and the natural Kokoro voices) on the iPad through sherpa-onnx.
/// One model is kept loaded at a time; switching voices loads the new one.
final class SherpaEngine {

  struct Model: Equatable {
    enum Kind: Equatable { case piper, kokoro(lexicon: String) }
    var kind: Kind
    var directory: URL
  }

  private let queue = DispatchQueue(label: "aloud.sherpa", qos: .userInitiated)
  private var tts: OpaquePointer?
  private var loaded: Model?

  deinit {
    if let tts { SherpaOnnxDestroyOfflineTts(tts) }
  }

  /// Renders text, returning mono samples and their sample rate.
  func generate(_ text: String, model: Model, speaker: Int) async throws -> (samples: [Float], rate: Double) {
    try await withCheckedThrowingContinuation { continuation in
      queue.async {
        do {
          let tts = try self.load(model)
          let audio = text.withCString { SherpaOnnxOfflineTtsGenerate(tts, $0, Int32(speaker), 1.0) }
          guard let audio else { throw ImportError("The voice produced no sound.") }
          defer { SherpaOnnxDestroyOfflineTtsGeneratedAudio(audio) }
          let count = Int(audio.pointee.n)
          let rate = Double(audio.pointee.sample_rate)
          guard count > 0, let samples = audio.pointee.samples else { throw ImportError("The voice produced no sound.") }
          continuation.resume(returning: (Array(UnsafeBufferPointer(start: samples, count: count)), rate))
        } catch {
          continuation.resume(throwing: error)
        }
      }
    }
  }

  /// Lets the loaded model go, freeing its memory.
  func unload() {
    queue.async {
      if let tts = self.tts { SherpaOnnxDestroyOfflineTts(tts) }
      self.tts = nil
      self.loaded = nil
    }
  }

  private func load(_ model: Model) throws -> OpaquePointer {
    if let tts, loaded == model { return tts }
    if let tts { SherpaOnnxDestroyOfflineTts(tts) }
    tts = nil
    loaded = nil

    var strings: [UnsafeMutablePointer<CChar>] = []
    defer { strings.forEach { free($0) } }
    func c(_ value: String) -> UnsafePointer<CChar> {
      let pointer = strdup(value)!
      strings.append(pointer)
      return UnsafePointer(pointer)
    }

    let dir = model.directory
    var config = SherpaOnnxOfflineTtsConfig()
    config.model.num_threads = Int32(max(2, min(4, ProcessInfo.processInfo.activeProcessorCount - 2)))
    config.model.debug = 0
    config.model.provider = c("cpu")
    config.max_num_sentences = 1
    config.silence_scale = 0.2

    switch model.kind {
    case .piper:
      let onnx = (try? FileManager.default.contentsOfDirectory(atPath: dir.path))?
        .first { $0.hasSuffix(".onnx") } ?? "model.onnx"
      config.model.vits.model = c(dir.appendingPathComponent(onnx).path)
      config.model.vits.tokens = c(dir.appendingPathComponent("tokens.txt").path)
      config.model.vits.data_dir = c(dir.appendingPathComponent("espeak-ng-data").path)
      config.model.vits.noise_scale = 0.667
      config.model.vits.noise_scale_w = 0.8
      config.model.vits.length_scale = 1.0
    case .kokoro(let lexicon):
      config.model.kokoro.model = c(dir.appendingPathComponent("model.int8.onnx").path)
      config.model.kokoro.voices = c(dir.appendingPathComponent("voices.bin").path)
      config.model.kokoro.tokens = c(dir.appendingPathComponent("tokens.txt").path)
      config.model.kokoro.data_dir = c(dir.appendingPathComponent("espeak-ng-data").path)
      config.model.kokoro.lexicon = c(dir.appendingPathComponent(lexicon).path)
      config.model.kokoro.length_scale = 1.0
    }

    guard let created = SherpaOnnxCreateOfflineTts(&config) else {
      throw ImportError("The voice could not be loaded. Removing it and downloading it again may help.")
    }
    tts = created
    loaded = model
    return created
  }
}
