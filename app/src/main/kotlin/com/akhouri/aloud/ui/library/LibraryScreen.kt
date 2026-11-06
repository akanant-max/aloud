package com.akhouri.aloud.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.akhouri.aloud.data.entity.BookEntity
import com.akhouri.aloud.data.entity.ImportState
import com.akhouri.aloud.graph
import com.akhouri.aloud.ui.BookCover
import com.akhouri.aloud.ui.EmptyState
import com.akhouri.aloud.ui.NowPlayingBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
  onOpenBook: (String) -> Unit,
  onOpenSettings: () -> Unit,
) {
  val context = LocalContext.current
  val appGraph = context.graph
  val scope = rememberCoroutineScope()
  val snackbars = remember { SnackbarHostState() }

  val books by remember { appGraph.database.bookDao().observeAll() }
    .collectAsStateWithLifecycle(initialValue = emptyList())
  var importing by remember { mutableStateOf(false) }
  var menuOpen by remember { mutableStateOf(false) }

  suspend fun importAll(uris: List<Uri>) {
    if (uris.isEmpty()) return
    importing = true
    var failures = 0
    uris.forEach { uri ->
      val result = appGraph.importer.import(uri)
      if (result.isFailure) {
        failures++
        snackbars.showSnackbar(
          result.exceptionOrNull()?.message ?: "One file could not be imported",
        )
      }
    }
    importing = false
    if (failures == 0 && uris.size > 1) {
      snackbars.showSnackbar("Imported ${uris.size} books")
    }
  }

  val pickFiles = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenMultipleDocuments(),
  ) { uris -> scope.launch { importAll(uris) } }

  val pickFolder = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenDocumentTree(),
  ) { treeUri ->
    if (treeUri == null) return@rememberLauncherForActivityResult
    scope.launch {
      val found = withContext(Dispatchers.IO) {
        DocumentFile.fromTreeUri(context, treeUri)
          ?.listFiles()
          ?.filter { file ->
            file.isFile && appGraph.importer.isSupported(file.type, file.name.orEmpty())
          }
          ?.map { it.uri }
          .orEmpty()
      }
      if (found.isEmpty()) {
        snackbars.showSnackbar("No EPUB, PDF or text files in that folder")
      } else {
        importAll(found)
      }
    }
  }

  Scaffold(
    snackbarHost = { SnackbarHost(snackbars) },
    topBar = {
      TopAppBar(
        title = { Text("Aloud") },
        actions = {
          IconButton(onClick = onOpenSettings) {
            Icon(Icons.Default.Settings, contentDescription = "Settings")
          }
        },
      )
    },
    bottomBar = {
      NowPlayingBar(
        onClick = { appGraph.narrator.state.value.bookId?.let(onOpenBook) },
      )
    },
    floatingActionButton = {
      Column(horizontalAlignment = Alignment.End) {
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
          DropdownMenuItem(
            text = { Text("Add books") },
            leadingIcon = { Icon(Icons.Default.InsertDriveFile, null) },
            onClick = {
              menuOpen = false
              pickFiles.launch(arrayOf("application/epub+zip", "application/pdf", "text/plain"))
            },
          )
          DropdownMenuItem(
            text = { Text("Add a folder") },
            leadingIcon = { Icon(Icons.Default.Folder, null) },
            onClick = {
              menuOpen = false
              pickFolder.launch(null)
            },
          )
        }
        FloatingActionButton(onClick = { menuOpen = true }) {
          if (importing) {
            CircularProgressIndicator(
              strokeWidth = 2.dp,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
          } else {
            Icon(Icons.Default.Add, contentDescription = "Add a book")
          }
        }
      }
    },
  ) { padding ->
    if (books.isEmpty()) {
      EmptyState(
        title = "No books yet",
        subtitle = "Tap + to add an EPUB, a PDF with selectable text, or a plain text file.",
        modifier = Modifier.padding(padding),
      )
    } else {
      LazyColumn(
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        items(books, key = { it.id }) { book ->
          BookRow(
            book = book,
            onClick = { if (book.importState == ImportState.READY) onOpenBook(book.id) },
            onDelete = {
              scope.launch { appGraph.database.bookDao().delete(book.id) }
            },
          )
        }
      }
    }
  }
}

@Composable
private fun BookRow(
  book: BookEntity,
  onClick: () -> Unit,
  onDelete: () -> Unit,
) {
  val context = LocalContext.current
  val progress by remember(book.id) {
    context.graph.database.progressDao().observe(book.id)
  }.collectAsStateWithLifecycle(initialValue = null)

  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 8.dp),
  ) {
    BookCover(
      coverPath = book.coverPath,
      modifier = Modifier.width(48.dp).aspectRatio(0.66f),
    )
    Column(
      modifier = Modifier
        .weight(1f)
        .padding(start = 12.dp),
    ) {
      Text(
        text = book.title,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = listOfNotNull(book.author, book.format.name).joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )

      when (book.importState) {
        ImportState.PENDING, ImportState.IMPORTING -> {
          Text(
            text = "Importing…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
          )
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        ImportState.FAILED -> Text(
          text = book.importError ?: "Import failed",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.error,
          maxLines = 3,
        )
        ImportState.READY -> {
          val read = progress?.paragraphIndex ?: 0
          val fraction = if (book.paragraphCount > 0) {
            read.toFloat() / book.paragraphCount
          } else {
            0f
          }
          if (read > 0) {
            Text(
              text = "${(fraction * 100).toInt()}% · ${book.chapterCount} chapters",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
              progress = { fraction.coerceIn(0f, 1f) },
              modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
          } else {
            Text(
              text = "${book.chapterCount} chapters · not started",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
    IconButton(onClick = onDelete) {
      Icon(Icons.Default.Delete, contentDescription = "Remove ${book.title}")
    }
  }
}
