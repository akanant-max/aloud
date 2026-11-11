package com.akhouri.aloud.ui.library

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.akhouri.aloud.data.BookWithProgress
import com.akhouri.aloud.data.FtsQuery
import com.akhouri.aloud.data.LibrarySort
import com.akhouri.aloud.data.LibraryView
import com.akhouri.aloud.data.SearchResult
import com.akhouri.aloud.data.Settings
import com.akhouri.aloud.data.entity.BookEntity
import com.akhouri.aloud.data.entity.CollectionEntity
import com.akhouri.aloud.data.entity.ImportState
import com.akhouri.aloud.graph
import com.akhouri.aloud.ui.BookCover
import com.akhouri.aloud.ui.EmptyState
import com.akhouri.aloud.ui.NowPlayingBar
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
  onOpenBook: (String) -> Unit,
  onOpenReader: (String) -> Unit,
  onOpenSettings: () -> Unit,
) {
  val context = LocalContext.current
  val appGraph = context.graph
  val bookDao = appGraph.database.bookDao()
  val collectionDao = appGraph.database.collectionDao()
  val scope = rememberCoroutineScope()
  val snackbars = remember { SnackbarHostState() }

  val books by remember { bookDao.observeAllWithProgress() }
    .collectAsStateWithLifecycle(initialValue = emptyList())
  val collections by remember { collectionDao.observeAll() }
    .collectAsStateWithLifecycle(initialValue = emptyList())
  val settings by remember { appGraph.settings.settings }
    .collectAsStateWithLifecycle(initialValue = Settings())

  var importing by remember { mutableStateOf(false) }
  var addMenuOpen by remember { mutableStateOf(false) }
  var sortMenuOpen by remember { mutableStateOf(false) }
  var query by rememberSaveable { mutableStateOf("") }
  var openCollectionId by rememberSaveable { mutableStateOf<Long?>(null) }

  var editing by remember { mutableStateOf<BookEntity?>(null) }
  var moving by remember { mutableStateOf<BookEntity?>(null) }
  var renaming by remember { mutableStateOf<CollectionEntity?>(null) }
  var coverTarget by remember { mutableStateOf<String?>(null) }

  var nameHits by remember { mutableStateOf<List<BookEntity>>(emptyList()) }
  var contentHits by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
  var searched by remember { mutableStateOf(false) }

  val openCollection = collections.firstOrNull { it.id == openCollectionId }
  val searching = query.trim().length >= 2

  // A book whose collection has been deleted counts as loose again.
  val knownCollectionIds = collections.map { it.id }.toSet()
  val visibleBooks: List<BookWithProgress> = if (openCollection != null) {
    books
      .filter { it.book.collectionId == openCollection.id }
      .sortedWith(compareBy({ it.book.collectionPosition }, { it.book.title.lowercase() }))
  } else {
    sortBooks(
      books.filter { it.book.collectionId == null || it.book.collectionId !in knownCollectionIds },
      settings.librarySort,
      settings.librarySortReversed,
    )
  }

  BackHandler(enabled = query.isNotEmpty() || openCollection != null) {
    if (query.isNotEmpty()) query = "" else openCollectionId = null
  }

  // Debounced, and it covers both the names of books and every word inside them.
  LaunchedEffect(query) {
    val text = query.trim()
    if (text.length < 2) {
      nameHits = emptyList()
      contentHits = emptyList()
      searched = false
      return@LaunchedEffect
    }
    delay(250)
    nameHits = runCatching { bookDao.searchByName(text) }.getOrDefault(emptyList())
    contentHits = FtsQuery.sanitise(text)?.let { match ->
      runCatching { appGraph.database.paragraphDao().searchEverywhere(match, 60) }.getOrNull()
    }.orEmpty()
    searched = true
  }

  suspend fun importAll(uris: List<Uri>) {
    if (uris.isEmpty()) return
    importing = true
    var added = 0
    uris.forEach { uri ->
      val result = appGraph.importer.import(uri)
      if (result.isSuccess) {
        added++
        // Imported from inside a collection, a book is filed there straight away.
        openCollection?.let { target ->
          val position = (bookDao.inCollection(target.id).maxOfOrNull { it.collectionPosition } ?: -1) + 1
          result.getOrNull()?.let { bookDao.setCollection(it, target.id, position) }
        }
      } else {
        snackbars.showSnackbar(result.exceptionOrNull()?.message ?: "One file could not be imported")
      }
    }
    importing = false
    if (added > 1) snackbars.showSnackbar("Imported $added books")
  }

  suspend fun moveTo(book: BookEntity, collectionId: Long?) {
    if (collectionId == null) {
      bookDao.setCollection(book.id, null, 0)
    } else {
      val position = (bookDao.inCollection(collectionId).maxOfOrNull { it.collectionPosition } ?: -1) + 1
      bookDao.setCollection(book.id, collectionId, position)
    }
  }

  /** Moves a book one place earlier or later in its collection's play order. */
  suspend fun shift(book: BookEntity, by: Int) {
    val collectionId = book.collectionId ?: return
    val ordered = bookDao.inCollection(collectionId).toMutableList()
    val from = ordered.indexOfFirst { it.id == book.id }
    val to = from + by
    if (from < 0 || to !in ordered.indices) return
    ordered.add(to, ordered.removeAt(from))
    ordered.forEachIndexed { index, entry -> bookDao.setCollection(entry.id, collectionId, index) }
  }

  suspend fun remove(book: BookEntity) {
    bookDao.delete(book.id)
    book.coverPath?.let { path -> runCatching { File(path).delete() } }
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

  val pickCover = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenDocument(),
  ) { imageUri ->
    val bookId = coverTarget
    coverTarget = null
    if (imageUri == null || bookId == null) return@rememberLauncherForActivityResult
    scope.launch {
      val saved = withContext(Dispatchers.IO) {
        runCatching {
          val bytes = context.contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
            ?: return@runCatching null
          val dir = File(context.filesDir, "covers").apply { mkdirs() }
          // A fresh name each time, so the image cache cannot keep showing the old cover.
          val file = File(dir, "$bookId-${System.currentTimeMillis()}.img")
          file.writeBytes(bytes)
          file.absolutePath
        }.getOrNull()
      }
      if (saved != null) {
        bookDao.setCover(bookId, saved)
        snackbars.showSnackbar("Cover updated")
      } else {
        snackbars.showSnackbar("That image could not be used")
      }
    }
  }

  Scaffold(
    snackbarHost = { SnackbarHost(snackbars) },
    topBar = {
      Column {
        TopAppBar(
          title = {
            Text(openCollection?.name ?: "Aloud", maxLines = 1, overflow = TextOverflow.Ellipsis)
          },
          navigationIcon = {
            if (openCollection != null) {
              IconButton(onClick = { openCollectionId = null }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the library")
              }
            }
          },
          actions = {
            if (openCollection == null) {
              IconButton(onClick = { sortMenuOpen = true }) {
                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
              }
              DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                LibrarySort.entries.forEach { sort ->
                  val selected = settings.librarySort == sort
                  DropdownMenuItem(
                    text = {
                      Text(
                        sortLabel(sort) +
                          if (selected) (if (settings.librarySortReversed) "  ↑" else "  ↓") else "",
                      )
                    },
                    leadingIcon = {
                      if (selected) Icon(Icons.Default.Check, contentDescription = null)
                    },
                    onClick = {
                      sortMenuOpen = false
                      scope.launch {
                        // Choosing the current order again flips its direction.
                        appGraph.settings.setLibrarySort(
                          sort,
                          if (selected) !settings.librarySortReversed else false,
                        )
                      }
                    },
                  )
                }
              }
            }
            IconButton(
              onClick = {
                scope.launch {
                  appGraph.settings.setLibraryView(
                    if (settings.libraryView == LibraryView.List) LibraryView.Grid else LibraryView.List,
                  )
                }
              },
            ) {
              if (settings.libraryView == LibraryView.List) {
                Icon(Icons.Default.GridView, contentDescription = "Show as a grid")
              } else {
                Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = "Show as a list")
              }
            }
            IconButton(onClick = onOpenSettings) {
              Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
          },
        )
        OutlinedTextField(
          value = query,
          onValueChange = { query = it },
          placeholder = { Text("Search books and what is inside them") },
          leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
          trailingIcon = {
            if (query.isNotEmpty()) {
              IconButton(onClick = { query = "" }) {
                Icon(Icons.Default.Close, contentDescription = "Clear the search")
              }
            }
          },
          singleLine = true,
          shape = RoundedCornerShape(28.dp),
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
        )
      }
    },
    bottomBar = {
      NowPlayingBar(onClick = { appGraph.narrator.state.value.bookId?.let(onOpenBook) })
    },
    floatingActionButton = {
      Column(horizontalAlignment = Alignment.End) {
        DropdownMenu(expanded = addMenuOpen, onDismissRequest = { addMenuOpen = false }) {
          DropdownMenuItem(
            text = { Text("Add books") },
            leadingIcon = { Icon(Icons.Default.InsertDriveFile, null) },
            onClick = {
              addMenuOpen = false
              pickFiles.launch(arrayOf("application/epub+zip", "application/pdf", "text/plain"))
            },
          )
          DropdownMenuItem(
            text = { Text("Add a folder") },
            leadingIcon = { Icon(Icons.Default.Folder, null) },
            onClick = {
              addMenuOpen = false
              pickFolder.launch(null)
            },
          )
        }
        FloatingActionButton(onClick = { addMenuOpen = true }) {
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
    val shownCollections = if (openCollection == null) collections else emptyList()

    val bookActions: (BookEntity) -> BookActions = { book ->
      BookActions(
        onOpen = { if (book.importState == ImportState.READY) onOpenBook(book.id) },
        onEdit = { editing = book },
        onMove = { moving = book },
        onEarlier = if (openCollection != null) { scope.launch { shift(book, -1) }; Unit } else null,
        onLater = if (openCollection != null) { scope.launch { shift(book, 1) }; Unit } else null,
        onRemove = { scope.launch { remove(book) }; Unit },
      )
    }

    val playAll: () -> Unit = {
      val first = visibleBooks.firstOrNull { it.book.importState == ImportState.READY && it.fraction < 0.995f }
        ?: visibleBooks.firstOrNull { it.book.importState == ImportState.READY }
      if (first != null) {
        scope.launch {
          appGraph.narrator.open(first.book.id)
          appGraph.narrator.play()
          onOpenReader(first.book.id)
        }
      }
    }

    when {
      searching -> SearchResults(
        padding = padding,
        nameHits = nameHits,
        contentHits = contentHits,
        searched = searched,
        query = query.trim(),
        onOpenBook = onOpenBook,
        onOpenPassage = { hit ->
          scope.launch {
            appGraph.narrator.open(hit.bookId, hit.paragraphIndex)
            onOpenReader(hit.bookId)
          }
        },
      )

      books.isEmpty() && collections.isEmpty() -> EmptyState(
        title = "No books yet",
        subtitle = "Tap + to add an EPUB, a PDF with selectable text, or a plain text file.",
        modifier = Modifier.padding(padding),
      )

      settings.libraryView == LibraryView.Grid -> LazyVerticalGrid(
        columns = GridCells.Adaptive(112.dp),
        contentPadding = PaddingValues(
          start = 10.dp,
          end = 10.dp,
          top = padding.calculateTopPadding(),
          bottom = padding.calculateBottomPadding() + 88.dp,
        ),
      ) {
        if (openCollection != null) {
          item(span = { GridItemSpan(maxLineSpan) }) {
            CollectionHeader(count = visibleBooks.size, onPlayAll = playAll)
          }
        }
        items(shownCollections, key = { "c-${it.id}" }) { collection ->
          CollectionTile(
            collection = collection,
            count = books.count { it.book.collectionId == collection.id },
            onOpen = { openCollectionId = collection.id },
            onRename = { renaming = collection },
            onDelete = {
              scope.launch {
                bookDao.clearCollection(collection.id)
                collectionDao.delete(collection.id)
              }
            },
          )
        }
        items(visibleBooks, key = { "b-${it.book.id}" }) { item ->
          BookTile(item = item, actions = bookActions(item.book))
        }
      }

      else -> LazyColumn(
        contentPadding = PaddingValues(
          top = padding.calculateTopPadding(),
          bottom = padding.calculateBottomPadding() + 88.dp,
        ),
      ) {
        if (openCollection != null) {
          item { CollectionHeader(count = visibleBooks.size, onPlayAll = playAll) }
        }
        items(shownCollections, key = { "c-${it.id}" }) { collection ->
          CollectionRow(
            collection = collection,
            count = books.count { it.book.collectionId == collection.id },
            onOpen = { openCollectionId = collection.id },
            onRename = { renaming = collection },
            onDelete = {
              scope.launch {
                bookDao.clearCollection(collection.id)
                collectionDao.delete(collection.id)
              }
            },
          )
        }
        items(visibleBooks, key = { "b-${it.book.id}" }) { item ->
          BookRow(item = item, actions = bookActions(item.book))
        }
      }
    }
  }

  editing?.let { book ->
    EditBookDialog(
      book = book,
      onDismiss = { editing = null },
      onSave = { title, author ->
        editing = null
        scope.launch { bookDao.editDetails(book.id, title, author) }
      },
      onPickCover = {
        coverTarget = book.id
        pickCover.launch(arrayOf("image/*"))
      },
      onRemoveCover = {
        scope.launch {
          bookDao.setCover(book.id, null)
          snackbars.showSnackbar("Cover removed")
        }
      },
    )
  }

  moving?.let { book ->
    MoveDialog(
      book = book,
      collections = collections,
      onDismiss = { moving = null },
      onPick = { collectionId ->
        moving = null
        scope.launch { moveTo(book, collectionId) }
      },
      onCreate = { name ->
        moving = null
        scope.launch {
          val id = collectionDao.insert(
            CollectionEntity(name = name, createdAt = System.currentTimeMillis()),
          )
          moveTo(book, id)
        }
      },
    )
  }

  renaming?.let { collection ->
    NameDialog(
      heading = "Rename collection",
      initial = collection.name,
      onDismiss = { renaming = null },
      onConfirm = { name ->
        renaming = null
        scope.launch { collectionDao.rename(collection.id, name) }
      },
    )
  }
}

// ---- pieces -------------------------------------------------------------------------------

private class BookActions(
  val onOpen: () -> Unit,
  val onEdit: () -> Unit,
  val onMove: () -> Unit,
  val onEarlier: (() -> Unit)?,
  val onLater: (() -> Unit)?,
  val onRemove: () -> Unit,
)

private fun sortLabel(sort: LibrarySort): String = when (sort) {
  LibrarySort.Recent -> "Recently opened"
  LibrarySort.Title -> "Title"
  LibrarySort.Author -> "Author"
  LibrarySort.Added -> "Date added"
  LibrarySort.Progress -> "Progress"
}

private fun sortBooks(
  books: List<BookWithProgress>,
  sort: LibrarySort,
  reversed: Boolean,
): List<BookWithProgress> {
  val sorted = when (sort) {
    LibrarySort.Recent -> books.sortedByDescending { it.book.lastOpenedAt ?: it.book.importedAt }
    LibrarySort.Title -> books.sortedBy { it.book.title.lowercase() }
    // Books with no author recorded go last rather than first.
    LibrarySort.Author -> books.sortedBy { (it.book.author ?: "￿").lowercase() }
    LibrarySort.Added -> books.sortedByDescending { it.book.importedAt }
    LibrarySort.Progress -> books.sortedByDescending { it.fraction }
  }
  return if (reversed) sorted.reversed() else sorted
}

@Composable
private fun BookMenu(expanded: Boolean, onDismiss: () -> Unit, actions: BookActions) {
  DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
    DropdownMenuItem(
      text = { Text("Edit title, author and cover") },
      onClick = { onDismiss(); actions.onEdit() },
    )
    DropdownMenuItem(
      text = { Text("Move to a collection") },
      onClick = { onDismiss(); actions.onMove() },
    )
    actions.onEarlier?.let { earlier ->
      DropdownMenuItem(text = { Text("Play earlier") }, onClick = { onDismiss(); earlier() })
    }
    actions.onLater?.let { later ->
      DropdownMenuItem(text = { Text("Play later") }, onClick = { onDismiss(); later() })
    }
    DropdownMenuItem(
      text = { Text("Remove from library") },
      onClick = { onDismiss(); actions.onRemove() },
    )
  }
}

@Composable
private fun BookStatus(item: BookWithProgress, compact: Boolean) {
  val book = item.book
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
      overflow = TextOverflow.Ellipsis,
    )
    ImportState.READY -> {
      val started = (item.progressParagraph ?: 0) > 0
      if (!compact) {
        Text(
          text = if (started) {
            "${(item.fraction * 100).toInt()}% · ${book.chapterCount} chapters"
          } else {
            "${book.chapterCount} chapters · not started"
          },
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (started) {
        LinearProgressIndicator(
          progress = { item.fraction },
          modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
      }
    }
  }
}

@Composable
private fun BookRow(item: BookWithProgress, actions: BookActions) {
  val book = item.book
  var menuOpen by remember { mutableStateOf(false) }

  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = actions.onOpen)
      .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
  ) {
    BookCover(coverPath = book.coverPath, modifier = Modifier.width(48.dp).aspectRatio(0.66f))
    Column(Modifier.weight(1f).padding(start = 12.dp)) {
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
      BookStatus(item, compact = false)
    }
    Box {
      IconButton(onClick = { menuOpen = true }) {
        Icon(Icons.Default.MoreVert, contentDescription = "Options for ${book.title}")
      }
      BookMenu(expanded = menuOpen, onDismiss = { menuOpen = false }, actions = actions)
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookTile(item: BookWithProgress, actions: BookActions) {
  val book = item.book
  var menuOpen by remember { mutableStateOf(false) }

  Column(
    Modifier
      .clip(RoundedCornerShape(10.dp))
      .combinedClickable(onClick = actions.onOpen, onLongClick = { menuOpen = true })
      .padding(6.dp),
  ) {
    Box {
      BookCover(coverPath = book.coverPath, modifier = Modifier.fillMaxWidth().aspectRatio(0.66f))
      BookMenu(expanded = menuOpen, onDismiss = { menuOpen = false }, actions = actions)
    }
    BookStatus(item, compact = true)
    Text(
      text = book.title,
      style = MaterialTheme.typography.labelLarge,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(top = 4.dp),
    )
    book.author?.let {
      Text(
        text = it,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun CollectionMenu(
  expanded: Boolean,
  onDismiss: () -> Unit,
  onRename: () -> Unit,
  onDelete: () -> Unit,
) {
  DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
    DropdownMenuItem(text = { Text("Rename") }, onClick = { onDismiss(); onRename() })
    DropdownMenuItem(
      text = { Text("Delete collection, keep its books") },
      onClick = { onDismiss(); onDelete() },
    )
  }
}

@Composable
private fun CollectionRow(
  collection: CollectionEntity,
  count: Int,
  onOpen: () -> Unit,
  onRename: () -> Unit,
  onDelete: () -> Unit,
) {
  var menuOpen by remember { mutableStateOf(false) }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onOpen)
      .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
  ) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier
        .width(48.dp)
        .aspectRatio(0.66f)
        .clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.tertiaryContainer),
    ) {
      Icon(
        Icons.Default.Folder,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onTertiaryContainer,
      )
    }
    Column(Modifier.weight(1f).padding(start = 12.dp)) {
      Text(
        text = collection.name,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = "Collection · $count book${if (count == 1) "" else "s"}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Box {
      IconButton(onClick = { menuOpen = true }) {
        Icon(Icons.Default.MoreVert, contentDescription = "Options for ${collection.name}")
      }
      CollectionMenu(menuOpen, { menuOpen = false }, onRename, onDelete)
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CollectionTile(
  collection: CollectionEntity,
  count: Int,
  onOpen: () -> Unit,
  onRename: () -> Unit,
  onDelete: () -> Unit,
) {
  var menuOpen by remember { mutableStateOf(false) }
  Column(
    Modifier
      .clip(RoundedCornerShape(10.dp))
      .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true })
      .padding(6.dp),
  ) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(0.66f)
        .clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.tertiaryContainer),
    ) {
      Icon(
        Icons.Default.Folder,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.size(40.dp),
      )
      CollectionMenu(menuOpen, { menuOpen = false }, onRename, onDelete)
    }
    Text(
      text = collection.name,
      style = MaterialTheme.typography.labelLarge,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(top = 4.dp),
    )
    Text(
      text = "$count book${if (count == 1) "" else "s"}",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun CollectionHeader(count: Int, onPlayAll: () -> Unit) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
  ) {
    Text(
      text = if (count == 0) {
        "Empty. Add books with +, or move them here from the library."
      } else {
        "Plays in this order, one book straight into the next."
      },
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.weight(1f).padding(end = 12.dp),
    )
    if (count > 0) {
      Button(onClick = onPlayAll) {
        Icon(Icons.Default.PlayArrow, contentDescription = null)
        Text("Play", modifier = Modifier.padding(start = 4.dp))
      }
    }
  }
}

@Composable
private fun SearchResults(
  padding: PaddingValues,
  nameHits: List<BookEntity>,
  contentHits: List<SearchResult>,
  searched: Boolean,
  query: String,
  onOpenBook: (String) -> Unit,
  onOpenPassage: (SearchResult) -> Unit,
) {
  LazyColumn(
    contentPadding = PaddingValues(
      top = padding.calculateTopPadding(),
      bottom = padding.calculateBottomPadding() + 88.dp,
    ),
  ) {
    if (nameHits.isNotEmpty()) {
      item { SectionLabel("Books") }
      items(nameHits, key = { "n-${it.id}" }) { book ->
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier
            .fillMaxWidth()
            .clickable { if (book.importState == ImportState.READY) onOpenBook(book.id) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
          BookCover(coverPath = book.coverPath, modifier = Modifier.width(40.dp).aspectRatio(0.66f))
          Column(Modifier.padding(start = 12.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            book.author?.let {
              Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
          }
        }
      }
    }
    if (contentHits.isNotEmpty()) {
      item { SectionLabel("Inside your books") }
      items(contentHits, key = { "p-${it.bookId}-${it.paragraphIndex}" }) { hit ->
        Column(
          Modifier
            .fillMaxWidth()
            .clickable { onOpenPassage(hit) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
          Text(highlighted(hit.snippet), style = MaterialTheme.typography.bodyMedium)
          Text(
            text = buildString {
              append(hit.bookTitle)
              append(" · ")
              append(hit.chapterTitle ?: "Chapter ${hit.chapterIndex + 1}")
              hit.page?.let { append(" · page $it") }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
          )
        }
        HorizontalDivider()
      }
    }
    if (searched && nameHits.isEmpty() && contentHits.isEmpty()) {
      item {
        Text(
          text = "Nothing in your library matches “$query”.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(24.dp),
        )
      }
    }
  }
}

@Composable
private fun SectionLabel(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
  )
}

/** SQLite marks the matched words in a snippet with square brackets; this makes them bold. */
private fun highlighted(snippet: String): AnnotatedString = buildAnnotatedString {
  var rest = snippet
  while (true) {
    val open = rest.indexOf('[')
    val close = if (open >= 0) rest.indexOf(']', open) else -1
    if (open < 0 || close < 0) {
      append(rest)
      break
    }
    append(rest.substring(0, open))
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(rest.substring(open + 1, close)) }
    rest = rest.substring(close + 1)
  }
}

// ---- dialogs ------------------------------------------------------------------------------

@Composable
private fun EditBookDialog(
  book: BookEntity,
  onDismiss: () -> Unit,
  onSave: (title: String, author: String?) -> Unit,
  onPickCover: () -> Unit,
  onRemoveCover: () -> Unit,
) {
  var title by remember(book.id) { mutableStateOf(book.title) }
  var author by remember(book.id) { mutableStateOf(book.author.orEmpty()) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Edit details") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
          value = title,
          onValueChange = { title = it },
          label = { Text("Title") },
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = author,
          onValueChange = { author = it },
          label = { Text("Author") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Row {
          TextButton(onClick = onPickCover) { Text("Choose a cover") }
          if (book.coverPath != null) {
            TextButton(onClick = onRemoveCover) { Text("Remove cover") }
          }
        }
      }
    },
    confirmButton = {
      TextButton(
        enabled = title.isNotBlank(),
        onClick = { onSave(title.trim(), author.trim().takeIf { it.isNotEmpty() }) },
      ) { Text("Save") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

@Composable
private fun MoveDialog(
  book: BookEntity,
  collections: List<CollectionEntity>,
  onDismiss: () -> Unit,
  onPick: (Long?) -> Unit,
  onCreate: (String) -> Unit,
) {
  var newName by remember { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Move to a collection") },
    text = {
      Column {
        collections.forEach { collection ->
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
              .fillMaxWidth()
              .clickable { onPick(collection.id) }
              .padding(vertical = 12.dp),
          ) {
            Icon(Icons.Default.Folder, contentDescription = null)
            Text(
              text = collection.name,
              modifier = Modifier.weight(1f).padding(start = 12.dp),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            if (book.collectionId == collection.id) {
              Icon(Icons.Default.Check, contentDescription = "Currently here")
            }
          }
        }
        if (book.collectionId != null) {
          Text(
            text = "Take out of its collection",
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().clickable { onPick(null) }.padding(vertical = 12.dp),
          )
        }
        OutlinedTextField(
          value = newName,
          onValueChange = { newName = it },
          label = { Text("New collection") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
      }
    },
    confirmButton = {
      TextButton(enabled = newName.isNotBlank(), onClick = { onCreate(newName.trim()) }) {
        Text("Create and move")
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

@Composable
private fun NameDialog(
  heading: String,
  initial: String,
  onDismiss: () -> Unit,
  onConfirm: (String) -> Unit,
) {
  var name by remember { mutableStateOf(initial) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(heading) },
    text = {
      OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
    },
    confirmButton = {
      TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) { Text("Save") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}
