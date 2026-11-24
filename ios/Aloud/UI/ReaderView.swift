import SwiftUI

/// The book's text, following along with the voice. Tap a paragraph to listen from there.
struct ReaderView: View {
  @EnvironmentObject private var library: Library
  @EnvironmentObject private var settings: Settings
  @EnvironmentObject private var narrator: Narrator
  let id: UUID
  let start: Position?

  @State private var chapter = 0
  @State private var follow = true
  @State private var marked: Int?

  var body: some View {
    if let book = library.book(id), let content = library.content(for: id) {
      reader(book, content)
    } else {
      Text("This book's text could not be found.").foregroundStyle(.secondary)
    }
  }

  private func reader(_ book: Book, _ content: BookContent) -> some View {
    let speaking = narrator.bookID == id ? narrator.current : nil
    let paragraphs = chapter < content.chapters.count ? content.chapters[chapter].paragraphs : []
    return ScrollViewReader { proxy in
      ScrollView {
        LazyVStack(alignment: .leading, spacing: settings.readerTextSize * 0.9) {
          Text(book.chapterTitle(chapter))
            .font(.system(size: settings.readerTextSize * 1.4, weight: .semibold, design: .serif))
            .padding(.bottom, 8)
          ForEach(Array(paragraphs.enumerated()), id: \.offset) { index, text in
            let isSpoken = speaking?.chapter == chapter && speaking?.paragraph == index
            Text(text)
              .font(.system(size: settings.readerTextSize, design: .serif))
              .lineSpacing(settings.readerTextSize * 0.35)
              .padding(.vertical, 6)
              .padding(.horizontal, 10)
              .frame(maxWidth: .infinity, alignment: .leading)
              .background(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                  .fill(isSpoken ? Theme.tint(settings, opacity: 0.22) : (marked == index ? Color.yellow.opacity(0.2) : Color.clear))
              )
              .contentShape(Rectangle())
              .onTapGesture {
                narrator.play(id, at: Position(chapter: chapter, paragraph: index, offset: 0))
                follow = true
              }
              .id(index)
          }
          chapterButtons(content)
        }
        .padding(.horizontal, 28)
        .padding(.vertical, 20)
        .frame(maxWidth: 760)
        .frame(maxWidth: .infinity)
      }
      .simultaneousGesture(DragGesture().onChanged { _ in follow = false })
      .onAppear {
        if let start {
          chapter = start.chapter
          marked = start.paragraph
          follow = false
          DispatchQueue.main.async { proxy.scrollTo(start.paragraph, anchor: .center) }
        } else if let speaking {
          chapter = speaking.chapter
          DispatchQueue.main.async { proxy.scrollTo(speaking.paragraph, anchor: .center) }
        } else {
          chapter = book.position.chapter
          DispatchQueue.main.async { proxy.scrollTo(book.position.paragraph, anchor: .top) }
        }
      }
      .onChange(of: speaking?.paragraph) { _, paragraph in
        guard follow, let speaking, let paragraph else { return }
        if speaking.chapter != chapter { chapter = speaking.chapter }
        withAnimation(.easeInOut(duration: 0.35)) { proxy.scrollTo(paragraph, anchor: .center) }
      }
      .onChange(of: speaking?.chapter) { _, newChapter in
        guard follow, let newChapter, newChapter != chapter else { return }
        chapter = newChapter
      }
      .toolbar {
        ToolbarItemGroup(placement: .topBarTrailing) {
          if !follow, narrator.bookID == id {
            Button {
              follow = true
              if let speaking {
                chapter = speaking.chapter
                withAnimation { proxy.scrollTo(speaking.paragraph, anchor: .center) }
              }
            } label: { Label("Follow the voice", systemImage: "location.fill") }
          }
          Menu {
            ForEach(0..<content.chapters.count, id: \.self) { index in
              Button(book.chapterTitle(index)) {
                chapter = index
                follow = false
                proxy.scrollTo(0, anchor: .top)
              }
            }
          } label: { Label("Chapters", systemImage: "list.bullet") }
          Button { settings.readerTextSize = max(13, settings.readerTextSize - 1) } label: { Label("Smaller text", systemImage: "textformat.size.smaller") }
          Button { settings.readerTextSize = min(34, settings.readerTextSize + 1) } label: { Label("Larger text", systemImage: "textformat.size.larger") }
        }
      }
    }
    .navigationTitle(book.title)
    .navigationBarTitleDisplayMode(.inline)
  }

  private func chapterButtons(_ content: BookContent) -> some View {
    HStack {
      if chapter > 0 {
        Button { chapter -= 1; follow = false } label: { Label("Previous chapter", systemImage: "chevron.left") }
      }
      Spacer()
      if chapter + 1 < content.chapters.count {
        Button { chapter += 1; follow = false } label: { Label("Next chapter", systemImage: "chevron.right").labelStyle(TrailingIcon()) }
      }
    }
    .buttonStyle(.bordered)
    .padding(.top, 24)
  }
}

struct TrailingIcon: LabelStyle {
  func makeBody(configuration: Configuration) -> some View {
    HStack(spacing: 6) { configuration.title; configuration.icon }
  }
}
