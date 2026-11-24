import AVFoundation

/// Every voice's audio is brought to one format before playback, so voices can change between
/// stretches without rebuilding the audio chain.
enum AudioTools {
  static let format = AVAudioFormat(standardFormatWithSampleRate: 24_000, channels: 1)!

  static func copy(_ buffer: AVAudioPCMBuffer) -> AVAudioPCMBuffer? {
    guard let copy = AVAudioPCMBuffer(pcmFormat: buffer.format, frameCapacity: buffer.frameLength) else { return nil }
    copy.frameLength = buffer.frameLength
    let source = UnsafeMutableAudioBufferListPointer(UnsafeMutablePointer(mutating: buffer.audioBufferList))
    let target = UnsafeMutableAudioBufferListPointer(copy.mutableAudioBufferList)
    for (s, t) in zip(source, target) {
      if let from = s.mData, let to = t.mData {
        memcpy(to, from, Int(min(s.mDataByteSize, t.mDataByteSize)))
      }
    }
    return copy
  }

  static func buffer(samples: [Float], rate: Double) -> AVAudioPCMBuffer? {
    guard let source = AVAudioFormat(standardFormatWithSampleRate: rate, channels: 1),
          let buffer = AVAudioPCMBuffer(pcmFormat: source, frameCapacity: AVAudioFrameCount(samples.count)) else { return nil }
    buffer.frameLength = AVAudioFrameCount(samples.count)
    samples.withUnsafeBufferPointer { pointer in
      buffer.floatChannelData![0].update(from: pointer.baseAddress!, count: samples.count)
    }
    return convert([buffer])
  }

  /// Joins buffers and converts them to the playback format, adding a short pause at the end.
  static func convert(_ buffers: [AVAudioPCMBuffer], trailingSilence: Double = 0.18) -> AVAudioPCMBuffer? {
    guard let first = buffers.first else { return nil }
    let inputFormat = first.format
    guard let converter = AVAudioConverter(from: inputFormat, to: format) else { return nil }
    let totalIn = buffers.reduce(0) { $0 + Int($1.frameLength) }
    let ratio = format.sampleRate / inputFormat.sampleRate
    let silence = Int(trailingSilence * format.sampleRate)
    let capacity = AVAudioFrameCount(Double(totalIn) * ratio) + AVAudioFrameCount(silence) + 4096
    guard let output = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: capacity) else { return nil }

    var index = 0
    var error: NSError?
    let status = converter.convert(to: output, error: &error) { _, outStatus in
      if index < buffers.count {
        let next = buffers[index]
        index += 1
        outStatus.pointee = .haveData
        return next
      }
      outStatus.pointee = .endOfStream
      return nil
    }
    if status == .error { return nil }

    // The pause between stretches.
    let used = Int(output.frameLength)
    let room = Int(output.frameCapacity) - used
    let pad = min(silence, room)
    if pad > 0, let data = output.floatChannelData?[0] {
      (data + used).initialize(repeating: 0, count: pad)
      output.frameLength = AVAudioFrameCount(used + pad)
    }
    return output
  }
}
