package com.akhouri.aloud.ui.book

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.akhouri.aloud.data.TextSize
import com.akhouri.aloud.graph
import com.akhouri.aloud.ui.BookCover
import com.akhouri.aloud.ui.player.formatRate
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(
  bookId: String,
  onBack: () -> Unit,
  onRead: () -> Unit,
  onSearch: () -> Unit,
) {
  val context = LocalContext.current
  val appGraph = context.graph
  val scope = rememberCoroutineScope()

  val book by remember(bookId) { appGraph.database.bookDao().observe(bookId) }
    .collectAsStateWithLifecycle(initialValue = null)
  val chapters by remember(bookId) { appGraph.database.chapterDao().observeForBook(bookId) }
    .collectAsStateWithLifecycle(initialValue = emptyList())
  val bookmarks by remember(bookId) { appGraph.database.bookmarkDao().observeForBook(bookId) }
    .collectAsStateWithLifecycle(initialValue = emptyList())
  val progress by remember(bookId) { appGraph.database.progressDao().observe(bookId) }
    .collectAsStateWithLifecycle(initialValue = null)

  val narration by appGraph.narrator.state.collectAsStateWithLifecycle()

  var total by remember(bookId) { mutableStateOf<TextSize?>(null) }
  var remaining by remember(bookId) { mutableStateOf<TextSize?>(null) }

  LaunchedEffect(bookId, book?.paragraphCount) {
    total = runCatching { appGraph.database.paragraphDao().sizeFrom(bookId, 0) }.getOrNull()
  }
  // Recounted every twenty paragraphs rather than on every sentence spoken.
  val position = progress?.paragraphIndex ?: 0
  LaunchedEffect(bookId, position / 20, book?.paragraphCount) {
    remaining = runCatching { appGraph.database.paragraphDao().sizeFrom(bookId, position) }
      .getOrNull()
  }

  val current = book

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(current?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
        actions = {
          IconButton(onClick = onSearch) {
            Icon(Icons.Default.Search, contentDescription = "Search in this book")
          }
        },
      )
    },
  ) { padding ->
    LazyColumn(contentPadding = padding) {

      item {
        Row(Modifier.padding(16.dp)) {
          BookCover(
            coverPath = current?.coverPath,
            modifier = Modifier.width(96.dp).aspectRatio(0.66f),
          )
          Column(Modifier.padding(start = 16.dp)) {
            Text(
              text = current?.title.orEmpty(),
              style = MaterialTheme.typography.headlineSmall,
            )
            current?.author?.let {
              Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
              )
            }
            val read = progress?.paragraphIndex ?: 0
            val total = current?.paragraphCount ?: 0
            if (total > 0) {
              val fraction = read.toFloat() / total
              Text(
                text = "${(fraction * 100).toInt()}% read",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
              )
              LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
              )
            }
          }
        }
      }

      item {
        val words = total?.words ?: 0L
        if (words > 0L) {
          // Characters a second at the speed chosen now: the learned pace of this voice,
          // scaled by the current speed, so changing speed changes the estimate at once.
          val pace = narration.charsPerSecondAt1x * narration.speechRate
          val leftSeconds = (remaining?.characters ?: 0L) / pace
          val totalSeconds = (total?.characters ?: 0L) / pace

          Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
            Text(
              text = "${grouped(words)} words · ${grouped((current?.paragraphCount ?: 0).toLong())} paragraphs",
              style = MaterialTheme.typography.bodyMedium,
            )
            Text(
              text = "About ${duration(leftSeconds)} left at ${formatRate(narration.speechRate)}× " +
                "· ${duration(totalSeconds)} in all",
              style = MaterialTheme.typography.bodyMedium,
            )
            Text(
              text = "Estimated from how fast your voice has actually been reading; it " +
                "sharpens as you listen.",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(top = 2.dp),
            )
          }
        }
      }

      item {
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
          Button(
            onClick = {
              scope.launch {
                appGraph.narrator.open(bookId)
                appGraph.narrator.play()
                onRead()
              }
            },
            modifier = Modifier.weight(1f),
          ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Text(
              text = if ((progress?.paragraphIndex ?: 0) > 0) "Resume" else "Listen",
              modifier = Modifier.padding(start = 8.dp),
            )
          }
          OutlinedButton(
            onClick = {
              scope.launch {
                appGraph.narrator.open(bookId, paragraphIndex = 0)
                onRead()
              }
            },
          ) {
            Icon(Icons.Default.Replay, contentDescription = "Start from the beginning")
          }
        }
      }

      if (bookmarks.isNotEmpty()) {
        item {
          SectionHeader("Bookmarks")
        }
        items(bookmarks, key = { "bm-${it.id}" }) { bookmark ->
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
              .fillMaxWidth()
              .clickable {
                scope.launch {
                  appGraph.narrator.open(bookId, bookmark.paragraphIndex, bookmark.charOffset)
                  onRead()
                }
              }
              .padding(horizontal = 16.dp, vertical = 10.dp),
          ) {
            Icon(Icons.Default.Bookmark, contentDescription = null)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
              Text(
                text = bookmark.title ?: "Paragraph ${bookmark.paragraphIndex + 1}",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              bookmark.note?.let {
                Text(
                  text = it,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 2,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }
            IconButton(
              onClick = { scope.launch { appGraph.database.bookmarkDao().delete(bookmark.id) } },
            ) {
              Icon(Icons.Default.Delete, contentDescription = "Delete bookmark")
            }
          }
        }
      }

      item { SectionHeader("Chapters") }

      items(chapters, key = { "ch-${it.id}" }) { chapter ->
        val reached = (progress?.paragraphIndex ?: -1) >= chapter.startParagraph
        Column(
          Modifier
            .fillMaxWidth()
            .clickable {
              scope.launch {
                appGraph.narrator.open(bookId, chapter.startParagraph)
                onRead()
              }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
          Text(
            text = chapter.title ?: "Chapter ${chapter.chapterIndex + 1}",
            style = MaterialTheme.typography.bodyLarge,
            color = if (reached) {
              MaterialTheme.colorScheme.onSurfaceVariant
            } else {
              MaterialTheme.colorScheme.onSurface
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = "${chapter.paragraphCount} paragraphs",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        HorizontalDivider()
      }
    }
  }
}

@Composable
private fun SectionHeader(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
  )
}

private fun grouped(value: Long): String = String.format(Locale.US, "%,d", value)

private fun duration(seconds: Float): String {
  val minutes = (seconds / 60f).toLong().coerceAtLeast(0L)
  val hours = minutes / 60
  val rest = minutes % 60
  return when {
    hours > 0 -> "$hours h $rest min"
    minutes > 0 -> "$minutes min"
    else -> "under a minute"
  }
}
