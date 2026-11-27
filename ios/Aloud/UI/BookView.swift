import PhotosUI
import SwiftUI

struct BookView: View {
  @EnvironmentObject private var library: Library
  @EnvironmentObject private var settings: Settings
  @EnvironmentObject private var narrator: Narrator
  let id: UUID
  @Binding var path: NavigationPath

  @State private var editing = false
  @State private var title = ""
  @State private var author = ""
  @State private var photo: PhotosPickerItem?
  @State private var showChapters = true

  var body: some View {
    if let book = library.book(id) {
      content(book)
    } else {
      Text("This book is no longer in the library.").foregroundStyle(.secondary)
    }
  }

  private func content(_ book: Book) -> some View {
    let playingThis = narrator.bookID == book.id && (narrator.isPlaying || narrator.isPreparing)
    let wordsLeft = Int(Double(book.wordCount) * (1 - book.progress()))
    return ScrollView {
      VStack(alignment: .leading, spacing: 22) {
        HStack(alignment: .top, spacing: 24) {
          CoverView(book: book, cornerRadius: 14)
            .frame(width: 180)
            .shadow(color: .black.opacity(0.2), radius: 12, y: 6)
          VStack(alignment: .leading, spacing: 10) {
            Text(book.title).font(.system(.largeTitle, design: .serif).weight(.semibold)).lineLimit(4)
            if let author = book.author { Text(author).font(.title3).foregroundStyle(.secondary) }
            if let collection = book.collection {
              Button { path.append(Route.collection(collection)) } label: {
                Label(collection, systemImage: "rectangle.stack").font(.subheadline)
              }
            }
            if book.started && !book.completed {
              ProgressBar(value: book.progress(), height: 6).frame(maxWidth: 320).padding(.top, 4)
            }
            Text(book.statusLine(settings: settings)).font(.subheadline).foregroundStyle(.secondary)
            HStack(spacing: 12) {
              Button {
                if narrator.bookID == book.id { narrator.togglePlayPause() } else { narrator.play(book.id) }
              } label: {
                Label(playingThis ? "Pause" : (book.started ? "Resume" : "Play"), systemImage: playingThis ? "pause.fill" : "play.fill")
                  .frame(minWidth: 120)
              }
              .buttonStyle(.borderedProminent)
              .controlSize(.large)
              Button { path.append(Route.reader(book.id, nil)) } label: {
                Label("Read along", systemImage: "text.book.closed")
              }
              .buttonStyle(.bordered)
              .controlSize(.large)
            }
            .padding(.top, 6)
          }
        }

        // The numbers.
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 140), spacing: 12)], spacing: 12) {
          stat("Words", Format.number(book.wordCount))
          stat("Paragraphs", Format.number(book.paragraphCount))
          stat("Chapters", Format.number(book.chapterCount))
          stat("Whole book", Format.duration(settings.listeningTime(words: book.wordCount)))
          stat("Left at \(Format.speed(settings.speed))", Format.duration(settings.listeningTime(words: book.completed ? 0 : wordsLeft)))
          stat("Format", book.format.label)
        }
        Text("Times are estimated from how fast your voices have been reading, and follow the speed you choose.")
          .font(.caption).foregroundStyle(.secondary)

        // Where the listener has been.
        VStack(alignment: .leading, spacing: 8) {
          Text("Where you've been").font(.title2.weight(.bold))
          HistoryList(bookID: book.id, limit: 8) { entry in
            narrator.play(book.id, at: entry.position)
          }
          .padding(.horizontal, 14)
          .padding(.vertical, 6)
          .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color(uiColor: .secondarySystemGroupedBackground)))
        }

        // Chapters.
        VStack(alignment: .leading, spacing: 0) {
          Button { withAnimation(.snappy) { showChapters.toggle() } } label: {
            HStack {
              Text("Chapters").font(.title2.weight(.bold))
              Text("\(book.chapterCount)").font(.subheadline.weight(.semibold)).padding(.horizontal, 8).padding(.vertical, 2)
                .background(Capsule().fill(Theme.tint(settings)))
              Spacer()
              Image(systemName: "chevron.down").rotationEffect(.degrees(showChapters ? 0 : -90)).foregroundStyle(.secondary)
            }
          }
          .buttonStyle(.plain)
          .padding(.bottom, 10)
          if showChapters {
            LazyVStack(spacing: 0) {
              ForEach(0..<book.chapterCount, id: \.self) { index in
                let isCurrent = !book.completed && book.started && index == book.position.chapter
                Button { narrator.play(book.id, at: Position(chapter: index, paragraph: 0, offset: 0)) } label: {
                  HStack {
                    Text("\(index + 1)").font(.caption.monospacedDigit()).foregroundStyle(.secondary).frame(width: 34, alignment: .leading)
                    Text(book.chapterTitle(index)).lineLimit(1)
                    Spacer()
                    if isCurrent { Image(systemName: "speaker.wave.2.fill").foregroundStyle(.tint) }
                    Text(Format.duration(settings.listeningTime(words: chapterWords(book, index))))
                      .font(.caption).foregroundStyle(.secondary)
                  }
                  .padding(.vertical, 11)
                  .padding(.horizontal, 14)
                  .background(isCurrent ? Theme.tint(settings) : Color.clear)
                  .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                Divider().padding(.leading, 48)
              }
            }
            .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color(uiColor: .secondarySystemGroupedBackground)))
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
          }
        }
      }
      .padding(24)
      .frame(maxWidth: 900, alignment: .leading)
      .frame(maxWidth: .infinity)
    }
    .navigationTitle("")
    .navigationBarTitleDisplayMode(.inline)
    .toolbar {
      Menu {
        Button { title = book.title; author = book.author ?? ""; editing = true } label: { Label("Edit title and author", systemImage: "pencil") }
        PhotosPicker(selection: $photo, matching: .images) { Label("Choose a cover", systemImage: "photo") }
        if book.hasCover {
          Button { library.setCover(book.id, image: nil) } label: { Label("Remove the cover", systemImage: "photo.badge.minus") }
        }
        Divider()
        BookMenu(book: book, path: $path)
      } label: {
        Label("More", systemImage: "ellipsis.circle")
      }
    }
    .onChange(of: photo) { _, item in
      guard let item else { return }
      Task {
        if let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data) {
          library.setCover(book.id, image: image)
        }
        photo = nil
      }
    }
    .alert("Edit details", isPresented: $editing) {
      TextField("Title", text: $title)
      TextField("Author", text: $author)
      Button("Save") {
        let cleanTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanAuthor = author.trimmingCharacters(in: .whitespacesAndNewlines)
        library.update(book.id) {
          if !cleanTitle.isEmpty { $0.title = cleanTitle }
          $0.author = cleanAuthor.isEmpty ? nil : cleanAuthor
        }
        narrator.updateNowPlaying()
      }
      Button("Cancel", role: .cancel) {}
    }
  }

  private func chapterWords(_ book: Book, _ index: Int) -> Int {
    guard index < book.chapterWordStarts.count else { return 0 }
    let start = book.chapterWordStarts[index]
    let end = index + 1 < book.chapterWordStarts.count ? book.chapterWordStarts[index + 1] : book.wordCount
    return max(0, end - start)
  }

  private func stat(_ label: String, _ value: String) -> some View {
    VStack(alignment: .leading, spacing: 4) {
      Text(value).font(.title3.weight(.semibold)).lineLimit(1).minimumScaleFactor(0.7)
      Text(label).font(.caption).foregroundStyle(.secondary)
    }
    .frame(maxWidth: .infinity, alignment: .leading)
    .padding(14)
    .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color(uiColor: .secondarySystemGroupedBackground)))
  }
}
