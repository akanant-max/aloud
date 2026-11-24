import SwiftUI

struct SettingsView: View {
  @EnvironmentObject private var settings: Settings
  @EnvironmentObject private var voices: Voices
  @EnvironmentObject private var narrator: Narrator

  var body: some View {
    let _ = voices.revision
    let chosen = voices.resolve(settings.voiceKey)
    let hidden = settings.hiddenVoices
    let natural = voices.natural()
    let piper = voices.piperInstalled()
    let system = voices.system(allLanguages: settings.showAllLanguages)
    let hiddenCount = (natural + piper + system).filter { hidden.contains($0.key) }.count

    Form {
      // MARK: Voice
      Section {
        if natural.isEmpty {
          downloadRow(
            title: "Get the natural voices",
            detail: "The closest to Google's voice on your phone · Heart, Sarah and 12 more · one \(Voices.naturalMegabytes) MB download",
            key: "natural"
          ) {
            voices.downloadNatural {
              if let heart = voices.natural().first { settings.voiceKey = heart.key }
            }
          }
        } else {
          ForEach(natural.filter { !hidden.contains($0.key) }) { voice in
            voiceRow(voice, chosen: chosen, removeLabel: "Take off the list") { hide(voice) }
          }
          Button(role: .destructive) {
            if chosen?.kind == .natural { settings.voiceKey = "" }
            voices.removeNatural()
          } label: {
            Label("Remove the natural voices (frees about 180 MB)", systemImage: "trash")
          }
        }
      } header: {
        Text("Natural voices")
      } footer: {
        Text("Natural voices run on this iPad, with no connection needed once downloaded. Take the ones you don't want off the list; the rest share one download.")
      }

      Section("Piper voices") {
        ForEach(piper.filter { !hidden.contains($0.key) }) { voice in
          voiceRow(voice, chosen: chosen, removeLabel: "Delete") {
            if chosen?.key == voice.key { settings.voiceKey = "" }
            voices.removePiper(String(voice.key.dropFirst("piper:".count)))
          }
        }
        ForEach(Voices.piperCatalogue.filter { entry in !piper.contains { $0.key == "piper:\(entry.id)" } }) { entry in
          downloadRow(title: entry.name, detail: "\(entry.note) · \(entry.megabytes) MB", key: entry.id) {
            voices.downloadPiper(entry)
          }
        }
      }

      Section {
        ForEach(system.filter { !hidden.contains($0.key) }) { voice in
          voiceRow(voice, chosen: chosen, removeLabel: "Take off the list") { hide(voice) }
        }
        Toggle("Show voices in every language", isOn: $settings.showAllLanguages)
      } header: {
        Text("iPad voices")
      } footer: {
        Text("Better iPad voices are free: Settings › Accessibility › Spoken Content › Voices › English, then download an Enhanced or Premium voice. It appears here afterwards.")
      }

      if hiddenCount > 0 {
        Section {
          Button {
            settings.hiddenVoices = []
          } label: {
            Label("Bring back \(hiddenCount) removed \(hiddenCount == 1 ? "voice" : "voices")", systemImage: "arrow.uturn.backward")
          }
        }
      }

      if let message = voices.message {
        Section { Text(message).font(.callout) }
      }

      // MARK: Listening
      Section("Listening") {
        Stepper("Speed: \(Format.speed(settings.speed))", value: $settings.speed, in: 0.5...4, step: 0.05)
        Picker("Skip buttons jump", selection: $settings.skipSeconds) {
          ForEach([10, 15, 30, 45, 60], id: \.self) { Text("\($0) seconds").tag($0) }
        }
        Toggle("Start the sentence again after a long pause", isOn: $settings.replayAfterPause)
        Stepper("Reader text size: \(Int(settings.readerTextSize))", value: $settings.readerTextSize, in: 13...34, step: 1)
      }

      Section("Sleep timer") {
        Toggle("Fade out at the end", isOn: $settings.fadeOutSleep)
      }

      // MARK: Appearance
      Section("Appearance") {
        Picker("Theme", selection: $settings.theme) {
          ForEach(ThemeMode.allCases) { Text($0.label).tag($0) }
        }
        Toggle("Pure black in the dark theme", isOn: $settings.pureBlack)
        Picker("Colour", selection: $settings.accent) {
          ForEach(AccentChoice.allCases) { choice in
            Label { Text(choice.label) } icon: { Circle().fill(choice.color).frame(width: 14, height: 14) }.tag(choice)
          }
        }
        Picker("Library", selection: $settings.layout) {
          Text("Grid").tag(LibraryLayout.grid)
          Text("List").tag(LibraryLayout.list)
        }
      }

      Section {
        Text("Open the Files app, go to On My iPad › Aloud › Add books here, and put books in. Aloud adds them the next time it opens, then clears the folder.")
          .font(.callout)
      } header: {
        Text("Adding many books at once")
      }

      Section("About") {
        LabeledContent("Version", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0")
        LabeledContent("Pace learned from", value: Format.duration(settings.learnedSeconds) + " of listening")
        Text("Everything stays on this iPad. Voices: Apple's built-in voices; Piper (rhasspy); Kokoro, run through sherpa-onnx. Design after Voice by Paul Woitaschek.")
          .font(.footnote).foregroundStyle(.secondary)
      }
    }
    .navigationTitle("Settings")
  }

  private func hide(_ voice: VoiceOption) {
    settings.hiddenVoices.insert(voice.key)
    if voices.resolve(settings.voiceKey)?.key == voice.key {
      let next = voices.all(allLanguages: settings.showAllLanguages).first { !settings.hiddenVoices.contains($0.key) }
      settings.voiceKey = next?.key ?? ""
    }
  }

  private func voiceRow(_ voice: VoiceOption, chosen: VoiceOption?, removeLabel: String, remove: @escaping () -> Void) -> some View {
    HStack(spacing: 12) {
      Image(systemName: chosen?.key == voice.key ? "largecircle.fill.circle" : "circle")
        .font(.title3)
        .foregroundStyle(.tint)
      VStack(alignment: .leading, spacing: 2) {
        Text(voice.name)
        Text(voice.detail).font(.caption).foregroundStyle(.secondary)
      }
      Spacer()
      if narrator.previewing == voice.key {
        ProgressView()
      } else {
        Button { narrator.preview(voice) } label: { Image(systemName: "play.circle").font(.title2) }
          .buttonStyle(.borderless)
          .accessibilityLabel("Hear \(voice.name)")
      }
      Button(role: .destructive, action: remove) { Image(systemName: "minus.circle").font(.title3) }
        .buttonStyle(.borderless)
        .accessibilityLabel("\(removeLabel) \(voice.name)")
    }
    .contentShape(Rectangle())
    .onTapGesture { settings.voiceKey = voice.key }
  }

  private func downloadRow(title: String, detail: String, key: String, start: @escaping () -> Void) -> some View {
    let progress = voices.downloads[key]
    return Button(action: start) {
      HStack(spacing: 12) {
        Image(systemName: "arrow.down.circle").font(.title3).foregroundStyle(.tint)
        VStack(alignment: .leading, spacing: 2) {
          Text(title).foregroundStyle(.primary)
          Text(progressText(progress) ?? detail).font(.caption).foregroundStyle(.secondary)
        }
        Spacer()
        if let progress, progress > 0 {
          ProgressView(value: progress).frame(width: 80)
        }
      }
    }
    .disabled(progress != nil)
  }

  private func progressText(_ progress: Double?) -> String? {
    guard let progress else { return nil }
    if progress == 0 { return "Waiting for the other downloads" }
    if progress >= 0.86 { return "Unpacking…" }
    return "Downloading… \(Int(progress / 0.85 * 100))%"
  }
}
