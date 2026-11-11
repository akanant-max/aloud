@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.akhouri.aloud.ui.theme.bookText
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

  val waitingImports by appGraph.importQueue.waiting.collectAsStateWithLifecycle()
  var addMenuOpen by remember { mutableStateOf(false) }
  var sortMenuOpen by remember { mutableStateOf(false) }
  var query by rememberSaveable { mutableStateOf("") }
  var searchOpen by rememberSaveable { mutableStateOf(false) }
  val searchFocus = remember { FocusRequester() }

  // Which shelves are folded away, kept as their names so it survives rotation.
  var folded by rememberSaveable { mutableStateOf("") }
  val foldedShelves = folded.split(',').filter { it.isNotEmpty() }.toSet()
  val narration by appGraph.narrator.state.collectAsStateWithLifecycle()
  var openCollectionId by rememberSaveable { mutableStateOf<Long?>(null) }

  var editing by remember { mutableStateOf<BookEntity?>(null) }
  var moving by remember { mutableStateOf<BookEntity?>(null) }
  var renaming by remember { mutableStateOf<CollectionEntity?>(null) }
  var coverTarget by remember { mutableStateOf<String?>(null) }

  // Multi-select: long-press a book to start, then tap others to add them.
  var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
  var movingSelection by remember { mutableStateOf(false) }
  var confirmingRemoval by remember { mutableStateOf(false) }

  var nameHits by remember { mutableStateOf<List<BookEntity>>(emptyList()) }
  var contentHits by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
  var searched by remember { mutableStateOf(false) }

  val openCollection = collections.firstOrNull { it.id == openCollectionId }
  val searching = searchOpen && query.trim().length >= 2

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

  // Only books still on screen count, so a selection cannot act on something out of sight.
  val selectedBooks = visibleBooks.map { it.book }.filter { it.id in selected }
  val selecting = selectedBooks.isNotEmpty()

  BackHandler(enabled = selecting || searchOpen || openCollection != null) {
    when {
      selecting -> selected = emptySet()
      searchOpen -> {
        searchOpen = false
        query = ""
      }
      else -> openCollectionId = null
    }
  }

  LaunchedEffect(searchOpen) {
    if (searchOpen) runCatching { searchFocus.requestFocus() }
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

  // Imports belong to the app, not to this screen, so they carry on if the screen is left.
  val importing = waitingImports > 0
  LaunchedEffect(Unit) {
    appGraph.importQueue.messages.collect { message -> snackbars.showSnackbar(message) }
  }

  fun importAll(uris: List<Uri>) {
    appGraph.importQueue.enqueue(uris, openCollection?.id)
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

  /** Back to the first word, and off the Ongoing shelf, as if it had never been opened. */
  suspend fun restart(book: BookEntity) {
    if (appGraph.narrator.state.value.bookId == book.id) {
      appGraph.narrator.stop()
      appGraph.narrator.open(book.id, paragraphIndex = 0)
    }
    appGraph.database.progressDao().delete(book.id)
    bookDao.setCompleted(book.id, false)
  }

  suspend fun remove(book: BookEntity) {
    bookDao.delete(book.id)
    book.coverPath?.let { path -> runCatching { File(path).delete() } }
  }

  val pickFiles = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenMultipleDocuments(),
  ) { uris -> importAll(uris) }

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
        snackbars.showSnackbar("No books Aloud can read in that folder")
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
            Text(
              text = if (selecting) "${selectedBooks.size} selected" else openCollection?.name ?: "Aloud",
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          },
          navigationIcon = {
            if (selecting) {
              IconButton(onClick = { selected = emptySet() }) {
                Icon(Icons.Default.Close, contentDescription = "Cancel the selection")
              }
            } else if (openCollection != null) {
              IconButton(onClick = { openCollectionId = null }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the library")
              }
            }
          },
          actions = {
            if (selecting) {
              IconButton(onClick = { selected = visibleBooks.map { it.book.id }.toSet() }) {
                Icon(Icons.Default.SelectAll, contentDescription = "Select all")
              }
              IconButton(onClick = { movingSelection = true }) {
                Icon(Icons.Default.Folder, contentDescription = "Move the selection to a collection")
              }
              IconButton(onClick = { confirmingRemoval = true }) {
                Icon(Icons.Default.Delete, contentDescription = "Remove the selection from the library")
              }
            } else {
              IconButton(
                onClick = {
                  searchOpen = !searchOpen
                  if (!searchOpen) query = ""
                },
              ) {
                Icon(
                  imageVector = if (searchOpen) Icons.Default.Close else Icons.Default.Search,
                  contentDescription = if (searchOpen) "Close the search" else "Search",
                )
              }
            }
            if (!selecting && openCollection == null) {
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
            if (!selecting) IconButton(
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
            if (!selecting) {
              IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
              }
            }
          },
        )
        // The field only takes up room while a search is under way.
        if (searchOpen && !selecting) OutlinedTextField(
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
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 8.dp)
            .focusRequester(searchFocus),
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
              // Kindle files rarely report a useful type, so the picker shows everything and the
              // importer decides what it can read.
              pickFiles.launch(arrayOf("*/*"))
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
    val shelves = buildShelves(books, collections, settings.librarySort, settings.librarySortReversed)

    val toggleFold: (Shelf) -> Unit = { shelf ->
      folded = (if (shelf.name in foldedShelves) foldedShelves - shelf.name else foldedShelves + shelf.name)
        .joinToString(",")
    }
    val deleteCollection: (CollectionEntity) -> Unit = { collection ->
      scope.launch {
        bookDao.clearCollection(collection.id)
        collectionDao.delete(collection.id)
      }
    }
    val playOrPause: (BookEntity) -> Unit = { book ->
      scope.launch {
        if (appGraph.narrator.state.value.bookId != book.id) appGraph.narrator.open(book.id)
        appGraph.narrator.togglePlayPause()
      }
    }

    val bookActions: (BookEntity) -> BookActions = { book ->
      val toggle = {
        selected = if (book.id in selected) selected - book.id else selected + book.id
      }
      BookActions(
        selected = book.id in selected,
        selecting = selecting,
        completed = book.completed,
        onToggleCompleted = { scope.launch { bookDao.setCompleted(book.id, !book.completed) }; Unit },
        onRestart = { scope.launch { restart(book) }; Unit },
        onToggle = toggle,
        // While selecting, a tap adds or removes the book instead of opening it.
        onOpen = {
          if (selecting) toggle() else if (book.importState == ImportState.READY) onOpenBook(book.id)
        },
        onEdit = { editing = book },
        onMove = { moving = book },
        onEarlier = (fun() { scope.launch { shift(book, -1) } }).takeIf { openCollection != null },
        onLater = (fun() { scope.launch { shift(book, 1) } }).takeIf { openCollection != null },
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
        subtitle = "Tap + to add a book: EPUB, PDF, Kindle (MOBI, AZW3), FB2, Word, HTML or plain text.",
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
          items(visibleBooks, key = { "b-${it.book.id}" }) { item ->
            BookTile(item = item, actions = bookActions(item.book))
          }
        } else {
          shelves.forEach { shelf ->
            if (shelf.isEmpty) return@forEach
            val isFolded = shelf.shelf.name in foldedShelves
            item(key = "h-${shelf.shelf.name}", span = { GridItemSpan(maxLineSpan) }) {
              ShelfHeader(shelf, isFolded, onToggle = { toggleFold(shelf.shelf) })
            }
            if (isFolded) return@forEach
            shelf.hero?.let { hero ->
              item(key = "hero", span = { GridItemSpan(maxLineSpan) }) {
                ContinueCard(
                  item = hero,
                  playing = narration.isPlaying && narration.bookId == hero.book.id,
                  actions = bookActions(hero.book),
                  onPlay = { playOrPause(hero.book) },
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                )
              }
            }
            items(shelf.collections, key = { "c-${it.first.id}" }) { (collection, count) ->
              CollectionTile(
                collection = collection,
                count = count,
                onOpen = { openCollectionId = collection.id },
                onRename = { renaming = collection },
                onDelete = { deleteCollection(collection) },
              )
            }
            items(shelf.books, key = { "b-${it.book.id}" }) { item ->
              BookTile(item = item, actions = bookActions(item.book))
            }
          }
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
          items(visibleBooks, key = { "b-${it.book.id}" }) { item ->
            BookRow(item = item, actions = bookActions(item.book))
          }
        } else {
          shelves.forEach { shelf ->
            if (shelf.isEmpty) return@forEach
            val isFolded = shelf.shelf.name in foldedShelves
            item(key = "h-${shelf.shelf.name}") {
              ShelfHeader(shelf, isFolded, onToggle = { toggleFold(shelf.shelf) })
            }
            if (isFolded) return@forEach
            shelf.hero?.let { hero ->
              item(key = "hero") {
                ContinueCard(
                  item = hero,
                  playing = narration.isPlaying && narration.bookId == hero.book.id,
                  actions = bookActions(hero.book),
                  onPlay = { playOrPause(hero.book) },
                  modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
              }
            }
            items(shelf.collections, key = { "c-${it.first.id}" }) { (collection, count) ->
              CollectionRow(
                collection = collection,
                count = count,
                onOpen = { openCollectionId = collection.id },
                onRename = { renaming = collection },
                onDelete = { deleteCollection(collection) },
              )
            }
            items(shelf.books, key = { "b-${it.book.id}" }) { item ->
              BookRow(item = item, actions = bookActions(item.book))
            }
          }
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
      currentCollectionId = book.collectionId,
      canTakeOut = book.collectionId != null,
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

  if (movingSelection && selecting) {
    val targets = selectedBooks
    MoveDialog(
      currentCollectionId = null,
      canTakeOut = targets.any { it.collectionId != null },
      collections = collections,
      onDismiss = { movingSelection = false },
      onPick = { collectionId ->
        movingSelection = false
        selected = emptySet()
        scope.launch { targets.forEach { moveTo(it, collectionId) } }
      },
      onCreate = { name ->
        movingSelection = false
        selected = emptySet()
        scope.launch {
          val id = collectionDao.insert(
            CollectionEntity(name = name, createdAt = System.currentTimeMillis()),
          )
          targets.forEach { moveTo(it, id) }
        }
      },
    )
  }

  if (confirmingRemoval && selecting) {
    val targets = selectedBooks
    AlertDialog(
      onDismissRequest = { confirmingRemoval = false },
      title = { Text("Remove ${targets.size} book${if (targets.size == 1) "" else "s"}?") },
      text = {
        Text(
          "They leave the library along with their bookmarks and saved places. The original " +
            "files on your phone are not touched.",
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            confirmingRemoval = false
            selected = emptySet()
            scope.launch { targets.forEach { remove(it) } }
          },
        ) { Text("Remove") }
      },
      dismissButton = { TextButton(onClick = { confirmingRemoval = false }) { Text("Cancel") } },
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
  val selected: Boolean,
  val selecting: Boolean,
  val completed: Boolean,
  val onToggleCompleted: () -> Unit,
  val onRestart: () -> Unit,
  val onToggle: () -> Unit,
  val onOpen: () -> Unit,
  val onEdit: () -> Unit,
  val onMove: () -> Unit,
  val onEarlier: (() -> Unit)?,
  val onLater: (() -> Unit)?,
  val onRemove: () -> Unit,
)

/** The three shelves of the library, in the order they are shown. */
private enum class Shelf(val title: String) {
  Ongoing("Ongoing"),
  Library("Library"),
  Completed("Completed"),
}

private class ShelfContent(
  val shelf: Shelf,
  /** The book last listened to, shown large at the head of the Ongoing shelf. */
  val hero: BookWithProgress?,
  val collections: List<Pair<CollectionEntity, Int>>,
  val books: List<BookWithProgress>,
) {
  val bookCount: Int get() = books.size + if (hero != null) 1 else 0
  val isEmpty: Boolean get() = bookCount == 0 && collections.isEmpty()
}

/** Completed if marked so, ongoing once it has been listened to at all, otherwise untouched. */
private fun shelfOf(item: BookWithProgress): Shelf = when {
  item.book.completed -> Shelf.Completed
  item.progressParagraph != null -> Shelf.Ongoing
  else -> Shelf.Library
}

private fun buildShelves(
  all: List<BookWithProgress>,
  collections: List<CollectionEntity>,
  sort: LibrarySort,
  reversed: Boolean,
): List<ShelfContent> {
  val known = collections.map { it.id }.toSet()
  val loose = all.filter { it.book.collectionId == null || it.book.collectionId !in known }

  val hero = all
    .filter { shelfOf(it) == Shelf.Ongoing && it.book.importState == ImportState.READY }
    .maxByOrNull { it.book.lastOpenedAt ?: 0L }

  // A collection sits where its books are: completed once all of them are, ongoing as soon
  // as any has been started, and in the library until then.
  val collectionsByShelf = collections
    .map { collection ->
      val members = all.filter { it.book.collectionId == collection.id }
      val shelf = when {
        members.isNotEmpty() && members.all { it.book.completed } -> Shelf.Completed
        members.any { shelfOf(it) != Shelf.Library } -> Shelf.Ongoing
        else -> Shelf.Library
      }
      shelf to (collection to members.size)
    }
    .groupBy({ it.first }, { it.second })

  return Shelf.entries.map { shelf ->
    ShelfContent(
      shelf = shelf,
      hero = hero.takeIf { shelf == Shelf.Ongoing },
      collections = collectionsByShelf[shelf].orEmpty(),
      books = sortBooks(
        loose.filter { shelfOf(it) == shelf && it.book.id != hero?.book?.id },
        sort,
        reversed,
      ),
    )
  }
}

@Composable
private fun ShelfHeader(content: ShelfContent, folded: Boolean, onToggle: () -> Unit) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onToggle)
      .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
  ) {
    Text(content.shelf.title, style = MaterialTheme.typography.titleLargeEmphasized)
    CountPill(Icons.AutoMirrored.Filled.MenuBook, content.bookCount, "books")
    if (content.collections.isNotEmpty()) {
      CountPill(Icons.Default.Folder, content.collections.size, "collections")
    }
    Box(Modifier.weight(1f))
    Icon(
      imageVector = if (folded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
      contentDescription = if (folded) "Show ${content.shelf.title}" else "Hide ${content.shelf.title}",
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun CountPill(icon: ImageVector, count: Int, what: String) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .padding(start = 8.dp)
      .clip(RoundedCornerShape(50))
      .background(MaterialTheme.colorScheme.secondaryContainer)
      .padding(horizontal = 10.dp, vertical = 2.dp),
  ) {
    Icon(
      imageVector = icon,
      contentDescription = what,
      tint = MaterialTheme.colorScheme.onSecondaryContainer,
      modifier = Modifier.size(14.dp),
    )
    Text(
      text = count.toString(),
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSecondaryContainer,
      modifier = Modifier.padding(start = 4.dp),
    )
  }
}

/** The book last listened to, given the full width and a play button of its own. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueCard(
  item: BookWithProgress,
  playing: Boolean,
  actions: BookActions,
  onPlay: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val book = item.book
  val colors = MaterialTheme.colorScheme
  Box(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(32.dp))
      .background(
        Brush.linearGradient(listOf(colors.primaryContainer, colors.tertiaryContainer)),
      )
      .combinedClickable(onClick = actions.onOpen, onLongClick = actions.onToggle)
      .padding(20.dp),
  ) {
    Column {
      Row {
        BookCover(coverPath = book.coverPath, modifier = Modifier.width(84.dp).aspectRatio(0.66f))
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
          Text(
            text = "Continue listening",
            style = MaterialTheme.typography.labelLargeEmphasized,
            color = colors.primary,
          )
          Text(
            text = book.title,
            style = MaterialTheme.typography.headlineSmallEmphasized,
            color = colors.onPrimaryContainer,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
          )
          book.author?.let {
            Text(
              text = it,
              style = MaterialTheme.typography.bodyLarge,
              color = colors.onPrimaryContainer.copy(alpha = 0.75f),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 16.dp),
      ) {
        Column(Modifier.weight(1f)) {
          LinearProgressIndicator(
            progress = { item.fraction },
            color = colors.primary,
            trackColor = colors.primary.copy(alpha = 0.2f),
            modifier = Modifier.fillMaxWidth(),
          )
          Text(
            text = "${(item.fraction * 100).toInt()}% listened",
            style = MaterialTheme.typography.labelLarge,
            color = colors.primary,
            modifier = Modifier.padding(top = 8.dp),
          )
        }
        FilledIconButton(
          onClick = onPlay,
          modifier = Modifier.padding(start = 16.dp).size(64.dp),
        ) {
          Icon(
            imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = if (playing) "Pause" else "Play",
            modifier = Modifier.size(32.dp),
          )
        }
      }
    }
  }
}

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
      text = { Text(if (actions.completed) "Mark as not completed" else "Mark as completed") },
      onClick = { onDismiss(); actions.onToggleCompleted() },
    )
    DropdownMenuItem(
      text = { Text("Start again from the beginning") },
      onClick = { onDismiss(); actions.onRestart() },
    )
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
    ImportState.READY -> if (book.completed) {
      Text(
        text = "Completed",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
      )
    } else {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookRow(item: BookWithProgress, actions: BookActions) {
  val book = item.book
  var menuOpen by remember { mutableStateOf(false) }

  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 12.dp, vertical = 4.dp)
      .clip(RoundedCornerShape(24.dp))
      .background(
        if (actions.selected) {
          MaterialTheme.colorScheme.secondaryContainer
        } else {
          MaterialTheme.colorScheme.surfaceContainer
        },
      )
      .combinedClickable(onClick = actions.onOpen, onLongClick = actions.onToggle)
      .padding(start = 10.dp, end = 2.dp, top = 10.dp, bottom = 10.dp),
  ) {
    BookCover(coverPath = book.coverPath, modifier = Modifier.width(52.dp).aspectRatio(0.66f))
    Column(Modifier.weight(1f).padding(start = 16.dp)) {
      Text(
        text = book.title,
        style = MaterialTheme.typography.titleMediumEmphasized.bookText(),
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
    if (actions.selecting) {
      Checkbox(checked = actions.selected, onCheckedChange = { actions.onToggle() })
    } else {
      Box {
        IconButton(onClick = { menuOpen = true }) {
          Icon(Icons.Default.MoreVert, contentDescription = "Options for ${book.title}")
        }
        BookMenu(expanded = menuOpen, onDismiss = { menuOpen = false }, actions = actions)
      }
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
      .background(
        if (actions.selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
      )
      .combinedClickable(onClick = actions.onOpen, onLongClick = actions.onToggle)
      .padding(6.dp),
  ) {
    Box {
      BookCover(coverPath = book.coverPath, modifier = Modifier.fillMaxWidth().aspectRatio(0.66f))
      if (actions.selecting) {
        Checkbox(
          checked = actions.selected,
          onCheckedChange = { actions.onToggle() },
          modifier = Modifier.align(Alignment.TopEnd),
        )
      } else {
        Box(Modifier.align(Alignment.TopEnd)) {
          IconButton(onClick = { menuOpen = true }) {
            Icon(
              Icons.Default.MoreVert,
              contentDescription = "Options for ${book.title}",
              tint = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
            )
          }
          BookMenu(expanded = menuOpen, onDismiss = { menuOpen = false }, actions = actions)
        }
      }
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
      .padding(horizontal = 12.dp, vertical = 4.dp)
      .clip(RoundedCornerShape(24.dp))
      .background(MaterialTheme.colorScheme.surfaceContainer)
      .clickable(onClick = onOpen)
      .padding(start = 10.dp, end = 2.dp, top = 10.dp, bottom = 10.dp),
  ) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier
        .width(52.dp)
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
  currentCollectionId: Long?,
  canTakeOut: Boolean,
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
            if (currentCollectionId == collection.id) {
              Icon(Icons.Default.Check, contentDescription = "Currently here")
            }
          }
        }
        if (canTakeOut) {
          Text(
            text = "Take out of the collection",
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
