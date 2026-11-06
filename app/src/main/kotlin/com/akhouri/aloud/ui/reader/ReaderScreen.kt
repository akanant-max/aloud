package com.akhouri.aloud.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.akhouri.aloud.data.entity.BookmarkEntity
import com.akhouri.aloud.data.entity.ParagraphEntity
import com.akhouri.aloud.graph
import com.akhouri.aloud.ui.player.PlayerSheet
import com.akhouri.aloud.ui.player.formatRate
import com.akhouri.aloud.ui.theme.ReaderTextStyle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
  bookId: String,
  onBack: () -> Unit,
  onSearch: () -> Unit,
) {
  val context = LocalContext.current
  val appGraph = context.graph
  val narrator = appGraph.narrator
  val scope = rememberCoroutineScope()

  val state by narrator.state.collectAsStateWithLifecycle()
  val listState = rememberLazyListState()

  var paragraphs by remember { mutableStateOf<List<ParagraphEntity>>(emptyList()) }
  var showPlayer by remember { mutableStateOf(false) }

  // Opening by navigation rather than from the book screen still needs the book loaded.
  LaunchedEffect(bookId) {
    if (state.bookId != bookId) narrator.open(bookId)
  }

  // Only the current chapter is held in memory, so a 900-page book costs no more than a short one.
  LaunchedEffect(bookId, state.chapterIndex) {
    paragraphs = appGraph.database.paragraphDao().forChapter(bookId, state.chapterIndex)
  }

  // Follows the voice. Manual scrolling is never fought, because this only fires when the
  // spoken paragraph actually changes.
  LaunchedEffect(state.paragraphIndex, paragraphs) {
    val target = paragraphs.indexOfFirst { it.paragraphIndex == state.paragraphIndex }
    if (target >= 0) {
      runCatching { listState.animateScrollToItem(target, scrollOffset = -120) }
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(
              text = state.chapterTitle ?: "Chapter ${state.chapterIndex + 1}",
              style = MaterialTheme.typography.titleMedium,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = state.bookTitle,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
        actions = {
          IconButton(
            onClick = {
              scope.launch {
                appGraph.database.bookmarkDao().insert(
                  BookmarkEntity(
                    bookId = bookId,
                    paragraphIndex = state.paragraphIndex,
                    charOffset = state.charOffset,
                    title = state.chapterTitle,
                    createdAt = System.currentTimeMillis(),
                  ),
                )
              }
            },
          ) {
            Icon(Icons.Default.BookmarkAdd, contentDescription = "Bookmark this spot")
          }
          IconButton(onClick = onSearch) {
            Icon(Icons.Default.Search, contentDescription = "Search in this book")
          }
        },
      )
    },
    bottomBar = {
      ReaderControls(
        isPlaying = state.isPlaying,
        isPreparing = state.isPreparing,
        speechRate = state.speechRate,
        onPlayPause = { scope.launch { narrator.togglePlayPause() } },
        onPrevious = { scope.launch { narrator.previousChapter() } },
        onNext = { scope.launch { narrator.nextChapter() } },
        onOpenPlayer = { showPlayer = true },
      )
    },
  ) { padding ->
    Column(Modifier.padding(padding)) {
      state.error?.let { message ->
        Surface(
          color = MaterialTheme.colorScheme.errorContainer,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(16.dp),
          )
        }
      }

      LazyColumn(
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
          start = 20.dp,
          end = 20.dp,
          top = 12.dp,
          bottom = 48.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        items(paragraphs, key = { it.paragraphIndex }) { paragraph ->
          val isCurrent = paragraph.paragraphIndex == state.paragraphIndex
          Text(
            text = paragraph.text,
            style = ReaderTextStyle,
            color = if (isCurrent) {
              MaterialTheme.colorScheme.onPrimaryContainer
            } else {
              MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier
              .clip(RoundedCornerShape(6.dp))
              .then(
                if (isCurrent) {
                  Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                } else {
                  Modifier
                },
              )
              .clickable {
                // Tapping a paragraph starts reading from it.
                scope.launch { narrator.seekTo(paragraph.paragraphIndex) }
              }
              .padding(horizontal = 6.dp, vertical = 4.dp),
          )
        }
      }
    }
  }

  if (showPlayer) {
    PlayerSheet(onDismiss = { showPlayer = false })
  }
}

@Composable
private fun ReaderControls(
  isPlaying: Boolean,
  isPreparing: Boolean,
  speechRate: Float,
  onPlayPause: () -> Unit,
  onPrevious: () -> Unit,
  onNext: () -> Unit,
  onOpenPlayer: () -> Unit,
) {
  Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceEvenly,
      modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
      TextButton(onClick = onOpenPlayer) {
        Icon(Icons.Default.Speed, contentDescription = null)
        Text(
          text = "${formatRate(speechRate)}×",
          modifier = Modifier.padding(start = 4.dp),
        )
      }
      IconButton(onClick = onPrevious) {
        Icon(Icons.Default.SkipPrevious, contentDescription = "Previous chapter")
      }
      FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(56.dp)) {
        Box(contentAlignment = Alignment.Center) {
          if (isPreparing) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
          } else {
            Icon(
              imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
              contentDescription = if (isPlaying) "Pause" else "Play",
            )
          }
        }
      }
      IconButton(onClick = onNext) {
        Icon(Icons.Default.SkipNext, contentDescription = "Next chapter")
      }
      TextButton(onClick = onOpenPlayer) { Text("More") }
    }
  }
}
