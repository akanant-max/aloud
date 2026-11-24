import SwiftUI

/// The strip along the bottom while a book is loaded.
struct PlayerBar: View {
  @EnvironmentObject private var narrator: Narrator
  @EnvironmentObject private var library: Library
  @EnvironmentObject private var settings: Settings
  let open: () -> Void

  var body: some View {
    if let book = narrator.book {
      HStack(spacing: 14) {
        CoverView(book: book, cornerRadius: 6).frame(width: 40)
        VStack(alignment: .leading, spacing: 2) {
          Text(book.title).font(.subheadline.weight(.semibold)).lineLimit(1)
          Text(subtitle(book)).font(.caption).foregroundStyle(narrator.error == nil ? Color.secondary : Color.red).lineLimit(1)
        }
        Spacer()
        Button { narrator.skip(seconds: -Double(settings.skipSeconds)) } label: {
          Image(systemName: "gobackward.\(settings.skipSeconds)").font(.title3)
        }
        Button { narrator.togglePlayPause() } label: {
          ZStack {
            if narrator.isPreparing && narrator.isPlaying {
              ProgressView()
            } else {
              Image(systemName: narrator.isPlaying ? "pause.fill" : "play.fill").font(.title2)
            }
          }
          .frame(width: 44, height: 44)
        }
        Button { narrator.skip(seconds: Double(settings.skipSeconds)) } label: {
          Image(systemName: "goforward.\(settings.skipSeconds)").font(.title3)
        }
      }
      .buttonStyle(.plain)
      .padding(.horizontal, 16)
      .padding(.vertical, 10)
      .background(.regularMaterial)
      .overlay(alignment: .top) { Divider() }
      .contentShape(Rectangle())
      .onTapGesture(perform: open)
    }
  }

  private func subtitle(_ book: Book) -> String {
    if let error = narrator.error { return error }
    if narrator.isPreparing && narrator.isPlaying { return "Getting the voice ready…" }
    return book.chapterTitle(narrator.current?.chapter ?? book.position.chapter)
  }
}

/// The full player.
struct PlayerView: View {
  @EnvironmentObject private var narrator: Narrator
  @EnvironmentObject private var library: Library
  @EnvironmentObject private var settings: Settings
  @EnvironmentObject private var voices: Voices
  let openReader: (UUID) -> Void
  @State private var dragging: Double?

  var body: some View {
    if let book = narrator.book {
      ScrollView {
        VStack(spacing: 22) {
          CoverView(book: book, cornerRadius: 18)
            .frame(maxWidth: 300)
            .shadow(color: .black.opacity(0.25), radius: 18, y: 10)
            .padding(.top, 28)
          VStack(spacing: 6) {
            Text(book.title).font(.system(.title2, design: .serif).weight(.semibold)).multilineTextAlignment(.center)
            Text(book.chapterTitle(chapter(book))).font(.headline).foregroundStyle(.secondary).multilineTextAlignment(.center)
            if let author = book.author { Text(author).font(.subheadline).foregroundStyle(.secondary) }
          }
          if let error = narrator.error {
            Text(error).font(.callout).foregroundStyle(.red).multilineTextAlignment(.center)
          }
          paragraphSlider(book)
          controls
          options(book)
          Button { openReader(book.id) } label: {
            Label("Read along", systemImage: "text.book.closed").frame(maxWidth: 300)
          }
          .buttonStyle(.bordered)
          .controlSize(.large)
        }
        .padding(.horizontal, 28)
        .padding(.bottom, 30)
        .frame(maxWidth: 620)
        .frame(maxWidth: .infinity)
      }
    } else {
      Text("Nothing is playing.").foregroundStyle(.secondary).padding()
    }
  }

  private func chapter(_ book: Book) -> Int { narrator.current?.chapter ?? book.position.chapter }

  @ViewBuilder
  private func paragraphSlider(_ book: Book) -> some View {
    if let content = library.content(for: book.id) {
      let chapter = chapter(book)
      let count = max(1, content.chapters[min(chapter, content.chapters.count - 1)].paragraphs.count)
      let paragraph = narrator.current?.paragraph ?? book.position.paragraph
      VStack(spacing: 4) {
        Slider(
          value: Binding(
            get: { dragging ?? Double(min(paragraph, count - 1)) },
            set: { dragging = $0 }
          ),
          in: 0...Double(max(1, count - 1)),
          step: 1
        ) { editing in
          if !editing, let value = dragging {
            narrator.jump(to: Position(chapter: chapter, paragraph: min(Int(value), count - 1), offset: 0))
            dragging = nil
          }
        }
        HStack {
          Text("Paragraph \(min(paragraph + 1, count)) of \(count)")
          Spacer()
          Text("Chapter \(chapter + 1) of \(content.chapters.count)")
        }
        .font(.caption.monospacedDigit())
        .foregroundStyle(.secondary)
      }
    }
  }

  private var controls: some View {
    HStack(spacing: 26) {
      Button { narrator.previousChapter() } label: { Image(systemName: "backward.end.fill").font(.title2) }
      Button { narrator.skip(seconds: -Double(settings.skipSeconds)) } label: { Image(systemName: "gobackward.\(settings.skipSeconds)").font(.title) }
      Button { narrator.togglePlayPause() } label: {
        ZStack {
          Circle().fill(.tint).frame(width: 84, height: 84)
          if narrator.isPreparing && narrator.isPlaying {
            ProgressView().tint(.white)
          } else {
            Image(systemName: narrator.isPlaying ? "pause.fill" : "play.fill").font(.system(size: 34)).foregroundStyle(.white)
          }
        }
      }
      Button { narrator.skip(seconds: Double(settings.skipSeconds)) } label: { Image(systemName: "goforward.\(settings.skipSeconds)").font(.title) }
      Button { narrator.nextChapter() } label: { Image(systemName: "forward.end.fill").font(.title2) }
    }
    .buttonStyle(.plain)
  }

  private func options(_ book: Book) -> some View {
    HStack(spacing: 12) {
      Menu {
        Button("Faster by 0.05×") { settings.speed = min(4, ((settings.speed + 0.05) * 100).rounded() / 100) }
        Button("Slower by 0.05×") { settings.speed = max(0.5, ((settings.speed - 0.05) * 100).rounded() / 100) }
        Divider()
        ForEach([0.75, 0.9, 1.0, 1.1, 1.25, 1.5, 1.75, 2.0, 2.25, 2.5, 3.0], id: \.self) { value in
          Button(Format.speed(value)) { settings.speed = value }
        }
      } label: {
        pill(Format.speed(settings.speed), systemImage: "speedometer")
      }
      Menu {
        Button("Off") { narrator.setSleepTimer(minutes: nil) }
        Button("End of chapter") { narrator.sleepAtEndOfChapter() }
        ForEach([5, 10, 15, 30, 45, 60, 90], id: \.self) { minutes in
          Button("\(minutes) minutes") { narrator.setSleepTimer(minutes: minutes) }
        }
      } label: {
        pill(sleepLabel, systemImage: "moon.zzz")
      }
      Menu {
        let visible = voices.all(allLanguages: settings.showAllLanguages).filter { !settings.hiddenVoices.contains($0.key) }
        ForEach(visible) { voice in
          Button {
            settings.voiceKey = voice.key
          } label: {
            if voices.resolve(settings.voiceKey)?.key == voice.key {
              Label(voice.name, systemImage: "checkmark")
            } else {
              Text("\(voice.name) — \(voice.detail)")
            }
          }
        }
      } label: {
        pill(voices.resolve(settings.voiceKey)?.name ?? "Voice", systemImage: "person.wave.2")
      }
    }
  }

  private var sleepLabel: String {
    if narrator.sleepAtChapterEnd { return "Chapter end" }
    if let remaining = narrator.sleepRemaining {
      let m = Int(remaining) / 60, s = Int(remaining) % 60
      return String(format: "%d:%02d", m, s)
    }
    return "Sleep"
  }

  private func pill(_ text: String, systemImage: String) -> some View {
    Label(text, systemImage: systemImage)
      .font(.subheadline.weight(.semibold))
      .lineLimit(1)
      .padding(.horizontal, 14).padding(.vertical, 10)
      .background(Capsule().fill(Theme.tint(settings)))
  }
}
