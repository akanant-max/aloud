package com.akhouri.aloud

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.akhouri.aloud.util.WindowChrome
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.akhouri.aloud.data.BookmarkEntity
import com.akhouri.aloud.ui.AloudRoot
import com.akhouri.aloud.ui.BackDispatcher
import com.akhouri.aloud.ui.FilePickers
import com.akhouri.aloud.ui.Navigator
import com.akhouri.aloud.ui.Screen
import com.akhouri.aloud.ui.theme.AloudTheme
import com.akhouri.aloud.util.CrashReporter
import com.akhouri.aloud.util.SingleInstance
import com.akhouri.aloud.util.WindowsSystem
import javax.imageio.ImageIO
import javax.swing.UIManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun main() {
  CrashReporter.install()

  // A second launch only brings the running copy forward.
  val showRequests = MutableStateFlow(0)
  if (!SingleInstance.claim { showRequests.value++ }) return

  // Windows' own look for the folder dialog.
  runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }

  val graph = AppGraph()
  graph.start()

  val icon = object {}.javaClass.getResourceAsStream("/icon.png")?.use { stream ->
    ImageIO.read(stream)?.let { BitmapPainter(it.toComposeImageBitmap()) }
  }

  application(exitProcessOnExit = true) {
    val settings by graph.settings.settings.collectAsState()
    val narration by graph.narrator.state.collectAsState()
    val navigator = remember { Navigator() }
    val backDispatcher = remember { BackDispatcher() }
    var visible by remember { mutableStateOf(true) }
    var systemDark by remember { mutableStateOf(WindowsSystem.prefersDark()) }

    val start = remember { graph.settings.current }
    val windowState = rememberWindowState(
      placement = if (start.windowMaximized) WindowPlacement.Maximized else WindowPlacement.Floating,
      position = if (start.windowX != null && start.windowY != null) {
        WindowPosition(start.windowX.dp, start.windowY.dp)
      } else {
        WindowPosition.PlatformDefault
      },
      size = DpSize(start.windowWidth.dp, start.windowHeight.dp),
    )

    fun quit() {
      graph.shutdown()
      exitApplication()
    }

    // Windows' light or dark setting can change while Aloud is open.
    LaunchedEffect(Unit) {
      while (true) {
        delay(15_000)
        systemDark = withContext(Dispatchers.IO) { WindowsSystem.prefersDark() }
      }
    }

    LaunchedEffect(Unit) {
      showRequests.drop(1).collect { visible = true }
    }

    // The window opens where it was left.
    LaunchedEffect(windowState) {
      snapshotFlow { Triple(windowState.placement, windowState.position, windowState.size) }
        .debounce(600)
        .collect { (placement, position, size) ->
          graph.settings.update { s ->
            if (placement == WindowPlacement.Maximized) {
              s.copy(windowMaximized = true)
            } else {
              s.copy(
                windowMaximized = false,
                windowX = position.x.value.takeIf { it.isFinite() }?.toInt() ?: s.windowX,
                windowY = position.y.value.takeIf { it.isFinite() }?.toInt() ?: s.windowY,
                windowWidth = size.width.value.toInt().coerceAtLeast(640),
                windowHeight = size.height.value.toInt().coerceAtLeast(480),
              )
            }
          }
        }
    }

    // Aloud's icon by the clock: playback controls without opening the window.
    if (icon != null) {
      Tray(
        icon = icon,
        tooltip = if (narration.hasBook) "Aloud — ${narration.bookTitle}" else "Aloud",
        onAction = { visible = true },
        menu = {
          if (narration.hasBook) {
            Item(if (narration.isPlaying) "Pause" else "Play", onClick = { graph.scope.launch { graph.narrator.togglePlayPause() } })
            Item("Next chapter", onClick = { graph.scope.launch { graph.narrator.nextChapter() } })
            Item("Previous chapter", onClick = { graph.scope.launch { graph.narrator.previousChapter() } })
            Separator()
          }
          Item("Show Aloud", onClick = { visible = true })
          Item("Quit Aloud", onClick = ::quit)
        },
      )
    }

    fun shortcut(event: KeyEvent): Boolean {
      if (event.type != KeyEventType.KeyDown) return false
      val narrator = graph.narrator
      val skip = graph.settings.current.skipParagraphs
      fun run(action: suspend () -> Unit): Boolean {
        graph.scope.launch { action() }
        return true
      }
      return when {
        event.key == Key.Spacebar && !event.isCtrlPressed -> run { narrator.togglePlayPause() }
        event.key == Key.MediaPlayPause -> run { narrator.togglePlayPause() }
        event.key == Key.MediaNext -> run { narrator.nextChapter() }
        event.key == Key.MediaPrevious -> run { narrator.previousChapter() }
        event.isCtrlPressed && event.key == Key.DirectionRight -> run { narrator.nextChapter() }
        event.isCtrlPressed && event.key == Key.DirectionLeft -> run { narrator.previousChapter() }
        event.isAltPressed && event.key == Key.DirectionLeft -> {
          if (!backDispatcher.handle()) navigator.pop()
          true
        }
        event.key == Key.DirectionRight -> run { narrator.skipParagraphs(skip) }
        event.key == Key.DirectionLeft -> run { narrator.skipParagraphs(-skip) }
        event.isCtrlPressed && event.key == Key.DirectionUp -> run { narrator.setSpeechRate(narrator.state.value.speechRate + 0.1f) }
        event.isCtrlPressed && event.key == Key.DirectionDown -> run { narrator.setSpeechRate(narrator.state.value.speechRate - 0.1f) }
        event.key == Key.Escape -> {
          if (!backDispatcher.handle()) navigator.pop()
          true
        }
        event.isCtrlPressed && event.key == Key.O -> {
          graph.importQueue.enqueue(FilePickers.openBooks(null, graph.importer.patterns))
          true
        }
        event.isCtrlPressed && event.key == Key.F -> {
          when (val screen = navigator.current) {
            is Screen.Book -> navigator.push(Screen.Search(screen.id))
            is Screen.Reader -> navigator.push(Screen.Search(screen.id))
            Screen.Library -> graph.send(AppCommand.Search)
            else -> Unit
          }
          true
        }
        event.isCtrlPressed && event.key == Key.Comma -> {
          navigator.push(Screen.Settings)
          true
        }
        event.isCtrlPressed && event.key == Key.B -> run {
          val state = narrator.state.value
          val id = state.bookId ?: return@run
          graph.bookmarks.insert(
            BookmarkEntity(
              bookId = id,
              paragraphIndex = state.paragraphIndex,
              charOffset = state.charOffset,
              title = state.chapterTitle,
              createdAt = System.currentTimeMillis(),
            ),
          )
        }
        event.isCtrlPressed && (event.key == Key.Equals || event.key == Key.Plus || event.key == Key.NumPadAdd) -> {
          graph.settings.update { it.copy(readerTextSize = (it.readerTextSize + 1).coerceAtMost(36)) }
          true
        }
        event.isCtrlPressed && (event.key == Key.Minus || event.key == Key.NumPadSubtract) -> {
          graph.settings.update { it.copy(readerTextSize = (it.readerTextSize - 1).coerceAtLeast(14)) }
          true
        }
        else -> false
      }
    }

    Window(
      onCloseRequest = {
        if (settings.closeToTray && icon != null) visible = false else quit()
      },
      visible = visible,
      state = windowState,
      title = if (narration.hasBook) "${narration.bookTitle} — Aloud" else "Aloud",
      icon = icon,
      onKeyEvent = ::shortcut,
    ) {
      LaunchedEffect(visible) {
        if (visible) {
          window.toFront()
          window.requestFocus()
        }
      }
      // Coming back to the window is a moment the listener is awake: remember where the book is,
      // and offer the way back if it read on while they slept.
      DisposableEffect(window) {
        val listener = object : java.awt.event.WindowAdapter() {
          override fun windowGainedFocus(e: java.awt.event.WindowEvent?) {
            graph.scope.launch { graph.narrator.noteAppOpened() }
          }
        }
        window.addWindowFocusListener(listener)
        onDispose { window.removeWindowFocusListener(listener) }
      }
      AloudTheme(
        themeMode = settings.themeMode,
        colorScheme = settings.themeColorScheme,
        pureBlack = settings.pureBlack,
        systemDark = systemDark,
      ) {
        // The title bar takes the app's colours and follows theme changes.
        val chrome = MaterialTheme.colorScheme
        LaunchedEffect(chrome.surface, chrome.onSurface, visible) {
          if (visible) {
            WindowChrome.apply(
              window,
              caption = chrome.surface.toArgb() and 0xFFFFFF,
              text = chrome.onSurface.toArgb() and 0xFFFFFF,
              dark = chrome.surface.luminance() < 0.5f,
            )
          }
        }
        AloudRoot(graph, navigator, backDispatcher)
      }
    }
  }
}
