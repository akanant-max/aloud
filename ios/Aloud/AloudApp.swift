import SwiftUI
import UniformTypeIdentifiers

/// Everything the app shares, made once.
@MainActor
final class AppModel: ObservableObject {
  let library = Library()
  let settings = Settings()
  let voices = Voices()
  let importer = Importer()
  let narrator: Narrator

  init() {
    narrator = Narrator(library: library, settings: settings, voices: voices)
    importer.attach(library)
  }
}

@main
struct AloudApp: App {
  @StateObject private var model = AppModel()
  @Environment(\.scenePhase) private var scenePhase

  var body: some Scene {
    WindowGroup {
      RootView()
        .environmentObject(model.library)
        .environmentObject(model.settings)
        .environmentObject(model.voices)
        .environmentObject(model.importer)
        .environmentObject(model.narrator)
        .onOpenURL { url in model.importer.enqueue([url]) }
    }
    .onChange(of: scenePhase) { _, phase in
      switch phase {
      case .active:
        model.importer.scanInbox()
      case .background, .inactive:
        model.narrator.save()
        model.library.saveNow()
      @unknown default:
        break
      }
    }
  }
}

enum Route: Hashable {
  case book(UUID)
  case reader(UUID, Position?)
  case collection(String)
  case settings
  case search
}

struct RootView: View {
  @EnvironmentObject private var settings: Settings
  @EnvironmentObject private var narrator: Narrator
  @EnvironmentObject private var importer: Importer
  @State private var path = NavigationPath()
  @State private var showPlayer = false
  @Environment(\.colorScheme) private var systemScheme

  var body: some View {
    NavigationStack(path: $path) {
      LibraryView(path: $path)
        .navigationDestination(for: Route.self) { route in
          switch route {
          case .book(let id): BookView(id: id, path: $path)
          case .reader(let id, let position): ReaderView(id: id, start: position)
          case .collection(let name): CollectionView(name: name, path: $path)
          case .settings: SettingsView()
          case .search: SearchView(path: $path)
          }
        }
    }
    .safeAreaInset(edge: .bottom, spacing: 0) {
      VStack(spacing: 0) {
        if let status = importer.status {
          HStack(spacing: 10) {
            ProgressView()
            Text(status).font(.subheadline).lineLimit(1)
            Spacer()
          }
          .padding(.horizontal, 16).padding(.vertical, 10)
          .background(.thinMaterial)
        }
        if narrator.bookID != nil {
          PlayerBar { showPlayer = true }
        }
      }
    }
    .sheet(isPresented: $showPlayer) {
      PlayerView(openReader: { id in
        showPlayer = false
        path.append(Route.reader(id, nil))
      })
      .presentationDragIndicator(.visible)
    }
    .alert("Library", isPresented: Binding(get: { importer.message?.isEmpty == false }, set: { if !$0 { importer.message = nil } })) {
      Button("OK", role: .cancel) { importer.message = nil }
    } message: {
      Text(importer.message ?? "")
    }
    .dropDestination(for: URL.self) { urls, _ in
      importer.enqueue(urls)
      return !urls.isEmpty
    }
    .tint(settings.accent.color)
    .preferredColorScheme(settings.theme.scheme)
    .background(Theme.background(settings, systemScheme).ignoresSafeArea())
  }
}

@MainActor
enum Theme {
  static func background(_ settings: Settings, _ scheme: ColorScheme) -> Color {
    let dark = settings.theme == .dark || (settings.theme == .system && scheme == .dark)
    if dark && settings.pureBlack { return .black }
    return Color(uiColor: .systemGroupedBackground)
  }

  /// A soft colour from the accent, for cards and islands.
  static func tint(_ settings: Settings, opacity: Double = 0.16) -> Color {
    settings.accent.color.opacity(opacity)
  }
}

/// The types the file picker offers. Anything else is skipped with a message.
enum BookTypes {
  static var all: [UTType] {
    var types: [UTType] = [.epub, .pdf, .plainText, .html, .folder]
    for ext in ["mobi", "azw", "azw3", "prc", "fb2", "docx", "md", "xhtml", "htm"] {
      if let type = UTType(filenameExtension: ext) { types.append(type) }
    }
    types.append(.data)
    return types
  }
}
