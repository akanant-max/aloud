package com.akhouri.aloud.ui.player

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.akhouri.aloud.data.HistoryEntry
import com.akhouri.aloud.data.ListeningHistory
import com.akhouri.aloud.graph
import kotlinx.coroutines.launch

/**
 * Shown after opening the app when narration ran on well past the last thing the listener did:
 * the way back to where they fell asleep.
 */
@Composable
fun PlaceSuggestionCard(modifier: Modifier = Modifier) {
  val appGraph = LocalContext.current.graph
  val history = appGraph.history
  val suggestion by history.suggestion.collectAsStateWithLifecycle()
  val narration by appGraph.narrator.state.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()
  val current = suggestion ?: return
  if (narration.bookId != current.bookId) return
  val entry = current.entry

  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    modifier = modifier.fillMaxWidth(),
  ) {
    Column(Modifier.padding(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Bedtime, contentDescription = null)
        Text(
          text = "Dozed off?",
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.padding(start = 10.dp),
        )
      }
      Text(
        text = "When you last used Aloud (${whenText(entry.time)}) you were in " +
          "${chapterName(entry.chapterTitle, entry.chapterIndex)}. " +
          "It has read on to ${chapterName(current.nowChapterTitle, current.nowChapterIndex)}.",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp),
      )
      if (entry.snippet.isNotBlank()) {
        Text(
          text = "“${entry.snippet}”",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(top = 6.dp),
        )
      }
      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 12.dp),
      ) {
        FilledTonalButton(onClick = {
          history.dismissSuggestion()
          scope.launch { appGraph.narrator.seekTo(entry.paragraphIndex, entry.charOffset) }
        }) {
          Text("Go back there")
        }
        TextButton(onClick = { history.dismissSuggestion() }) { Text("I'm following") }
      }
    }
  }
}

/** The places remembered for a book, newest first. Tapping one goes back to it. */
@Composable
fun HistoryList(
  bookId: String,
  limit: Int = 12,
  onPick: (HistoryEntry) -> Unit,
) {
  val history = LocalContext.current.graph.history
  val revision by history.revision.collectAsStateWithLifecycle()
  val entries = remember(bookId, revision) { history.forBook(bookId) }
  if (entries.isEmpty()) {
    Text(
      text = "Places you press play, pause, jump from or open the app at will be listed here, " +
        "so you can find your way back after dozing off.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    return
  }
  Column {
    entries.take(limit).forEach { entry -> HistoryRow(entry) { onPick(entry) } }
  }
}

@Composable
fun HistoryRow(entry: HistoryEntry, onClick: () -> Unit) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 10.dp),
  ) {
    Icon(iconFor(entry.reason), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    Column(Modifier.weight(1f).padding(start = 14.dp)) {
      Text(
        text = chapterName(entry.chapterTitle, entry.chapterIndex),
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (entry.snippet.isNotBlank()) {
        Text(
          text = entry.snippet,
          style = MaterialTheme.typography.bodySmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Text(
        text = "${entry.reason} · ${whenText(entry.time)}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

private fun iconFor(reason: String): ImageVector = when (reason) {
  ListeningHistory.PRESSED_PLAY -> Icons.Default.PlayArrow
  ListeningHistory.PAUSED -> Icons.Default.Pause
  ListeningHistory.OPENED_APP -> Icons.AutoMirrored.Filled.OpenInNew
  ListeningHistory.BEFORE_JUMP -> Icons.AutoMirrored.Filled.Redo
  ListeningHistory.SLEEP_TIMER -> Icons.Default.Bedtime
  else -> Icons.Default.History
}

private fun chapterName(title: String?, index: Int): String =
  title?.takeIf { it.isNotBlank() } ?: "Chapter ${index + 1}"

private fun whenText(time: Long): String =
  DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString() +
    ", " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(time))
