import SwiftUI

struct LibraryView: View {
  @EnvironmentObject private var library: Library
  @EnvironmentObject private var settings: Settings
  @EnvironmentObject private var narrator: Narrator
  @EnvironmentObject private var importer: Importer
  @Binding var path: NavigationPath

  @State private var picking = false
  @State private var selecting = false
  @State private var selection = Set<UUID>()
  @State private var askCollection = false
  @State private var collectionName = ""
  @State private var confirmDelete = false

  var body: some View {
    ScrollView {
      if library.books.isEmpty {
        EmptyLibrary { picking = true }
      } else {
        LazyVStack(alignment: .leading, spacing: 18) {
          if !selecting, let continuing { ContinueCard(book: continuing) }
          shelf("Ongoing", books: ongoing, collections: [])
          shelf("Library", books: unstarted, collections: collectionsHere)
          shelf("Completed", books: completed, collections: [])
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
      }
    }
    .navigationTitle(selecting ? "\(selection.count) selected" : "Aloud")
    .toolbar { toolbar }
    .fileImporter(isPresented: $picking, allowedContentTypes: BookTypes.all, allowsMultipleSelection: true) { result in
      if case .success(let urls) = result { importer.enqueue(urls) }
    }
    .alert("Move to a collection", isPresented: $askCollection) {
      TextField("Collection name", text: $collectionName)
      Button("Move") {
        library.move(selection, toCollection: collectionName)
        endSelection()
      }
      Button("Cancel", role: .cancel) {}
    } message: {
      Text(library.collections.isEmpty ? "Books in a collection are kept together, like an anthology." : "Existing: \(library.collections.joined(separator: ", "))")
    }
    .confirmationDialog("Remove \(selection.count) books from the library?", isPresented: $confirmDelete, titleVisibility: .visible) {
      Button("Remove", role: .destructive) {
        if let id = narrator.bookID, selection.contains(id) { narrator.stop() }
        library.delete(selection)
        endSelection()
      }
    }
  }

  // MARK: Shelves

  private var continuing: Book? {
    library.books.filter { !$0.completed && $0.lastPlayed != nil }.max { ($0.lastPlayed ?? .distantPast) < ($1.lastPlayed ?? .distantPast) }
  }

  private var ongoing: [Book] {
    library.sorted(library.books.filter { $0.started && !$0.completed && $0.id != continuing?.id }, by: settings.sort)
  }

  private var unstarted: [Book] {
    library.sorted(library.books.filter { !$0.started && !$0.completed && $0.collection == nil }, by: settings.sort)
  }

  private var completed: [Book] {
    library.sorted(library.books.filter { $0.completed }, by: settings.sort)
  }

  /// Collections whose books have not all been started or finished show on the Library shelf.
  private var collectionsHere: [String] {
    library.collections.filter { name in library.books.contains { $0.collection == name && !$0.started && !$0.completed } }
  }

  @ViewBuilder
  private func shelf(_ title: String, books: [Book], collections: [String]) -> some View {
    let count = books.count + collections.count
    if count > 0 {
      let collapsed = settings.collapsedShelves.contains(title)
      VStack(alignment: .leading, spacing: 12) {
        Button {
          withAnimation(.snappy) {
            if collapsed { settings.collapsedShelves.remove(title) } else { settings.collapsedShelves.insert(title) }
          }
        } label: {
          HStack(spacing: 8) {
            Text(title).font(.title2.weight(.bold))
            Text("\(count)")
              .font(.subheadline.weight(.semibold))
              .padding(.horizontal, 8).padding(.vertical, 2)
              .background(Capsule().fill(Theme.tint(settings)))
            Spacer()
            Image(systemName: "chevron.down")
              .rotationEffect(.degrees(collapsed ? -90 : 0))
              .foregroundStyle(.secondary)
          }
          .contentShape(Rectangle())
        }
        .buttonStyle(.plain)

        if !collapsed {
          if settings.layout == .grid {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 150, maximum: 200), spacing: 18)], alignment: .leading, spacing: 22) {
              ForEach(collections, id: \.self) { name in collectionTile(name) }
              ForEach(books) { book in gridItem(book) }
            }
          } else {
            VStack(spacing: 8) {
              ForEach(collections, id: \.self) { name in collectionRow(name) }
              ForEach(books) { book in listRow(book) }
            }
          }
        }
      }
    }
  }

  private func gridItem(_ book: Book) -> some View {
    Button { tapped(book) } label: {
      VStack(alignment: .leading, spacing: 6) {
        ZStack(alignment: .topTrailing) {
          CoverView(book: book)
          if selecting {
            Image(systemName: selection.contains(book.id) ? "checkmark.circle.fill" : "circle")
              .font(.title2)
              .foregroundStyle(selection.contains(book.id) ? AnyShapeStyle(.tint) : AnyShapeStyle(.white))
              .shadow(radius: 2)
              .padding(8)
          }
        }
        Text(book.title).font(.subheadline.weight(.semibold)).lineLimit(2)
        if let author = book.author { Text(author).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
        if book.started && !book.completed { ProgressBar(value: book.progress(), height: 4) }
      }
    }
    .buttonStyle(.plain)
    .contextMenu { BookMenu(book: book, path: $path) }
  }

  private func listRow(_ book: Book) -> some View {
    Button { tapped(book) } label: {
      HStack(spacing: 14) {
        if selecting {
          Image(systemName: selection.contains(book.id) ? "checkmark.circle.fill" : "circle")
            .font(.title3).foregroundStyle(.tint)
        }
        CoverView(book: book, cornerRadius: 6).frame(width: 46)
        VStack(alignment: .leading, spacing: 3) {
          Text(book.title).font(.body.weight(.semibold)).lineLimit(1)
          Text([book.author, book.statusLine(settings: settings)].compactMap { $0 }.joined(separator: " · "))
            .font(.caption).foregroundStyle(.secondary).lineLimit(1)
          if book.started && !book.completed { ProgressBar(value: book.progress(), height: 3).frame(maxWidth: 220) }
        }
        Spacer()
      }
      .padding(10)
      .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color(uiColor: .secondarySystemGroupedBackground)))
    }
    .buttonStyle(.plain)
    .contextMenu { BookMenu(book: book, path: $path) }
  }

  private func collectionTile(_ name: String) -> some View {
    let books = library.books.filter { $0.collection == name }
    return Button { path.append(Route.collection(name)) } label: {
      VStack(alignment: .leading, spacing: 6) {
        ZStack {
          RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Theme.tint(settings, opacity: 0.25))
          VStack(spacing: 8) {
            Image(systemName: "rectangle.stack.fill").font(.system(size: 38)).foregroundStyle(.tint)
            Text("\(books.count) books").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
          }
        }
        .aspectRatio(2.0 / 3.0, contentMode: .fit)
        Text(name).font(.subheadline.weight(.semibold)).lineLimit(2)
        Text("Collection").font(.caption).foregroundStyle(.secondary)
      }
    }
    .buttonStyle(.plain)
  }

  private func collectionRow(_ name: String) -> some View {
    let books = library.books.filter { $0.collection == name }
    return Button { path.append(Route.collection(name)) } label: {
      HStack(spacing: 14) {
        Image(systemName: "rectangle.stack.fill").font(.title2).foregroundStyle(.tint).frame(width: 46)
        VStack(alignment: .leading) {
          Text(name).font(.body.weight(.semibold))
          Text("Collection · \(books.count) books").font(.caption).foregroundStyle(.secondary)
        }
        Spacer()
        Image(systemName: "chevron.right").foregroundStyle(.tertiary)
      }
      .padding(10)
      .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color(uiColor: .secondarySystemGroupedBackground)))
    }
    .buttonStyle(.plain)
  }

  private func tapped(_ book: Book) {
    if selecting {
      if selection.contains(book.id) { selection.remove(book.id) } else { selection.insert(book.id) }
    } else {
      path.append(Route.book(book.id))
    }
  }

  private func endSelection() {
    selecting = false
    selection.removeAll()
    collectionName = ""
  }

  // MARK: Toolbar

  @ToolbarContentBuilder
  private var toolbar: some ToolbarContent {
    if selecting {
      ToolbarItem(placement: .topBarLeading) {
        Button("Done") { endSelection() }
      }
      ToolbarItemGroup(placement: .topBarTrailing) {
        Button("Select all") { selection = Set(library.books.map(\.id)) }
        Menu {
          Button { askCollection = true } label: { Label("Move to collection", systemImage: "rectangle.stack") }
          Button { library.move(selection, toCollection: nil); endSelection() } label: { Label("Take out of collections", systemImage: "rectangle.stack.badge.minus") }
          Button {
            for id in selection { library.update(id) { $0.completed = true; $0.position = .start } }
            endSelection()
          } label: { Label("Mark as finished", systemImage: "checkmark.circle") }
          Button {
            for id in selection { library.update(id) { $0.completed = false; $0.position = .start; $0.lastPlayed = nil } }
            endSelection()
          } label: { Label("Mark as not started", systemImage: "arrow.counterclockwise") }
          Divider()
          Button(role: .destructive) { confirmDelete = true } label: { Label("Remove", systemImage: "trash") }
        } label: {
          Label("Actions", systemImage: "ellipsis.circle")
        }
        .disabled(selection.isEmpty)
      }
    } else {
      ToolbarItemGroup(placement: .topBarTrailing) {
        Button { path.append(Route.search) } label: { Label("Search", systemImage: "magnifyingglass") }
        Button { picking = true } label: { Label("Add books", systemImage: "plus") }
        Menu {
          Picker("Sort by", selection: $settings.sort) {
            ForEach(LibrarySort.allCases) { Text($0.label).tag($0) }
          }
          Picker("Show as", selection: $settings.layout) {
            Label("Grid", systemImage: "square.grid.2x2").tag(LibraryLayout.grid)
            Label("List", systemImage: "list.bullet").tag(LibraryLayout.list)
          }
          Divider()
          Button { selecting = true } label: { Label("Select books", systemImage: "checkmark.circle") }
            .disabled(library.books.isEmpty)
        } label: {
          Label("View", systemImage: "line.3.horizontal.decrease.circle")
        }
        Button { path.append(Route.settings) } label: { Label("Settings", systemImage: "gearshape") }
      }
    }
  }
}

/// The big card for the book last listened to.
struct ContinueCard: View {
  @EnvironmentObject private var narrator: Narrator
  @EnvironmentObject private var settings: Settings
  let book: Book

  var body: some View {
    let playingThis = narrator.bookID == book.id && (narrator.isPlaying || narrator.isPreparing)
    HStack(spacing: 18) {
      CoverView(book: book, cornerRadius: 10).frame(width: 96)
      VStack(alignment: .leading, spacing: 6) {
        Text("Continue listening").font(.caption.weight(.semibold)).textCase(.uppercase).foregroundStyle(.secondary)
        Text(book.title).font(.system(.title3, design: .serif).weight(.semibold)).lineLimit(2)
        Text(book.chapterTitle(book.position.chapter)).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
        ProgressBar(value: book.progress(), height: 5).padding(.top, 4)
        Text(book.statusLine(settings: settings)).font(.caption).foregroundStyle(.secondary)
      }
      Spacer(minLength: 0)
      Button {
        if narrator.bookID == book.id { narrator.togglePlayPause() } else { narrator.play(book.id) }
      } label: {
        Image(systemName: playingThis ? "pause.fill" : "play.fill")
          .font(.title)
          .frame(width: 64, height: 64)
          .background(Circle().fill(.tint))
          .foregroundStyle(.white)
      }
      .buttonStyle(.plain)
    }
    .padding(18)
    .background(RoundedRectangle(cornerRadius: 26, style: .continuous).fill(Theme.tint(settings, opacity: 0.22)))
  }
}

/// The actions on a book, shared by the library's long-press menu and the book page.
struct BookMenu: View {
  @EnvironmentObject private var library: Library
  @EnvironmentObject private var narrator: Narrator
  let book: Book
  @Binding var path: NavigationPath

  var body: some View {
    Button { narrator.play(book.id) } label: { Label(book.started ? "Resume" : "Play", systemImage: "play") }
    Button { path.append(Route.book(book.id)) } label: { Label("Details", systemImage: "info.circle") }
    Button { path.append(Route.reader(book.id, nil)) } label: { Label("Read along", systemImage: "text.book.closed") }
    Divider()
    if book.completed {
      Button { library.update(book.id) { $0.completed = false } } label: { Label("Mark as not finished", systemImage: "circle") }
    } else {
      Button {
        if narrator.bookID == book.id { narrator.stop() }
        library.update(book.id) { $0.completed = true; $0.position = .start }
      } label: { Label("Mark as finished", systemImage: "checkmark.circle") }
    }
    Button {
      if narrator.bookID == book.id { narrator.jump(to: .start) }
      library.update(book.id) { $0.position = .start; $0.completed = false }
    } label: { Label("Start over", systemImage: "arrow.counterclockwise") }
    if book.collection != nil {
      Button { library.move([book.id], toCollection: nil) } label: { Label("Take out of “\(book.collection ?? "")”", systemImage: "rectangle.stack.badge.minus") }
    }
    if !library.collections.isEmpty {
      Menu {
        ForEach(library.collections, id: \.self) { name in
          Button(name) { library.move([book.id], toCollection: name) }
        }
      } label: { Label("Add to collection", systemImage: "rectangle.stack.badge.plus") }
    }
    Divider()
    Button(role: .destructive) {
      if narrator.bookID == book.id { narrator.stop() }
      library.delete([book.id])
    } label: { Label("Remove from library", systemImage: "trash") }
  }
}

struct CollectionView: View {
  @EnvironmentObject private var library: Library
  @EnvironmentObject private var settings: Settings
  let name: String
  @Binding var path: NavigationPath
  @State private var renaming = false
  @State private var newName = ""

  var body: some View {
    let books = library.sorted(library.books.filter { $0.collection == name }, by: .title)
    ScrollView {
      LazyVGrid(columns: [GridItem(.adaptive(minimum: 150, maximum: 200), spacing: 18)], alignment: .leading, spacing: 22) {
        ForEach(books) { book in
          Button { path.append(Route.book(book.id)) } label: {
            VStack(alignment: .leading, spacing: 6) {
              CoverView(book: book)
              Text(book.title).font(.subheadline.weight(.semibold)).lineLimit(2)
              Text(book.statusLine(settings: settings)).font(.caption).foregroundStyle(.secondary)
            }
          }
          .buttonStyle(.plain)
          .contextMenu { BookMenu(book: book, path: $path) }
        }
      }
      .padding(20)
    }
    .navigationTitle(name)
    .toolbar {
      Button("Rename") { newName = name; renaming = true }
    }
    .alert("Rename collection", isPresented: $renaming) {
      TextField("Name", text: $newName)
      Button("Rename") {
        library.renameCollection(name, to: newName)
        if !path.isEmpty { path.removeLast() }
      }
      Button("Cancel", role: .cancel) {}
    }
  }
}
