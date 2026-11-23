@file:OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)

package com.akhouri.aloud.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import com.akhouri.aloud.AppGraph
import com.akhouri.aloud.ui.book.BookScreen
import com.akhouri.aloud.ui.library.LibraryScreen
import com.akhouri.aloud.ui.player.PlayerBar
import com.akhouri.aloud.ui.reader.ReaderScreen
import com.akhouri.aloud.ui.search.SearchScreen
import com.akhouri.aloud.ui.settings.SettingsScreen
import com.akhouri.aloud.util.CrashReporter
import java.awt.Frame
import java.awt.datatransfer.DataFlavor
import java.io.File

val LocalGraph = staticCompositionLocalOf<AppGraph> { error("No app graph") }
val LocalWindow = staticCompositionLocalOf<Frame?> { null }
val LocalBackDispatcher = staticCompositionLocalOf { BackDispatcher() }

sealed interface Screen {
  data object Library : Screen
  data class Book(val id: String) : Screen
  data class Reader(val id: String) : Screen
  data class Search(val id: String) : Screen
  data object Settings : Screen
}

/** The stack of screens; the last one is on show. */
class Navigator {
  var stack by mutableStateOf(listOf<Screen>(Screen.Library))
    private set

  val current: Screen get() = stack.last()

  fun push(screen: Screen) {
    if (current != screen) stack = stack + screen
  }

  /** Swaps the screen on show for another, so going back skips it. */
  fun replace(screen: Screen) {
    stack = stack.dropLast(1) + screen
  }

  fun pop(): Boolean {
    if (stack.size <= 1) return false
    stack = stack.dropLast(1)
    return true
  }
}

/** What Esc, Alt+Left and the mouse's back button do: the innermost thing that wants it. */
class BackDispatcher {
  class Entry(var enabled: Boolean, var onBack: () -> Unit)

  private val entries = mutableListOf<Entry>()

  fun add(entry: Entry) {
    entries += entry
  }

  fun remove(entry: Entry) {
    entries -= entry
  }

  fun handle(): Boolean {
    val entry = entries.lastOrNull { it.enabled } ?: return false
    entry.onBack()
    return true
  }
}

/** Lets a screen claim the back action while [enabled], as Android's BackHandler does. */
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
  val dispatcher = LocalBackDispatcher.current
  val entry = remember { BackDispatcher.Entry(enabled, onBack) }
  entry.enabled = enabled
  entry.onBack = onBack
  DisposableEffect(entry) {
    dispatcher.add(entry)
    onDispose { dispatcher.remove(entry) }
  }
}

/** Everything inside the window. */
@Composable
fun FrameWindowScope.AloudRoot(graph: AppGraph, navigator: Navigator, backDispatcher: BackDispatcher) {
  var crashReport by remember { mutableStateOf(CrashReporter.lastReport()) }
  val snackbars = remember { SnackbarHostState() }
  var dropping by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) {
    graph.importQueue.messages.collect { snackbars.showSnackbar(it) }
  }

  // Books dragged in from Explorer are added wherever the window is.
  val dropTarget = remember {
    object : DragAndDropTarget {
      override fun onEntered(event: DragAndDropEvent) {
        dropping = true
      }

      override fun onExited(event: DragAndDropEvent) {
        dropping = false
      }

      override fun onEnded(event: DragAndDropEvent) {
        dropping = false
      }

      override fun onDrop(event: DragAndDropEvent): Boolean {
        dropping = false
        val transferable = event.awtTransferable
        if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return false
        val files = (transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
          ?.filterIsInstance<File>()
          .orEmpty()
        if (files.isEmpty()) return false
        graph.importQueue.enqueue(files)
        return true
      }
    }
  }

  CompositionLocalProvider(
    LocalGraph provides graph,
    LocalWindow provides window,
    LocalBackDispatcher provides backDispatcher,
  ) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
      val report = crashReport
      if (report != null) {
        CrashReportScreen(report = report, onDismiss = {
          CrashReporter.clear()
          crashReport = null
        })
        return@Surface
      }

      Box(
        Modifier.fillMaxSize().dragAndDropTarget(
          shouldStartDragAndDrop = { event ->
            event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
          },
          target = dropTarget,
        ),
      ) {
        Column(Modifier.fillMaxSize()) {
          Box(Modifier.weight(1f)) {
            when (val screen = navigator.current) {
              Screen.Library -> LibraryScreen(
                onOpenBook = { navigator.push(Screen.Book(it)) },
                onOpenReader = { navigator.push(Screen.Reader(it)) },
                onOpenSettings = { navigator.push(Screen.Settings) },
              )
              is Screen.Book -> BookScreen(
                bookId = screen.id,
                onBack = { navigator.pop() },
                onRead = { navigator.push(Screen.Reader(screen.id)) },
                onSearch = { navigator.push(Screen.Search(screen.id)) },
              )
              is Screen.Reader -> ReaderScreen(
                bookId = screen.id,
                onBack = { navigator.pop() },
                onSearch = { navigator.push(Screen.Search(screen.id)) },
              )
              is Screen.Search -> SearchScreen(
                bookId = screen.id,
                onBack = { navigator.pop() },
                onJumpTo = { navigator.replace(Screen.Reader(screen.id)) },
              )
              Screen.Settings -> SettingsScreen(onBack = { navigator.pop() })
            }
          }
          PlayerBar(onOpenReader = { id ->
            if ((navigator.current as? Screen.Reader)?.id != id) navigator.push(Screen.Reader(id))
          })
        }

        SnackbarHost(snackbars, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))

        if (dropping) {
          Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
              .fillMaxSize()
              .padding(16.dp)
              .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f), RoundedCornerShape(28.dp))
              .border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
          ) {
            Text(
              text = "Drop books or folders to add them",
              style = MaterialTheme.typography.headlineSmall,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
          }
        }
      }
    }
  }
}
