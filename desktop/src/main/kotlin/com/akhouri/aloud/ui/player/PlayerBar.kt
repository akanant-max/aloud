package com.akhouri.aloud.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.akhouri.aloud.data.TextSize
import com.akhouri.aloud.ui.BookCover
import com.akhouri.aloud.ui.LocalGraph
import com.akhouri.aloud.ui.theme.bookText
import java.util.Locale
import kotlinx.coroutines.launch

private val SPEED_PRESETS = listOf(0.75f, 1.0f, 1.1f, 1.25f, 1.5f, 1.75f, 2.0f, 2.25f, 2.5f, 3.0f)
private val SLEEP_PRESETS_MIN = listOf(10, 15, 30, 45, 60, 90)

/**
 * The player along the bottom of the window, present on every screen once a book is open:
 * everything for listening is always one click away.
 */
@Composable
fun PlayerBar(onOpenReader: (String) -> Unit) {
  val graph = LocalGraph.current
  val narrator = graph.narrator
  val narration by narrator.state.collectAsState()
  val sleep by graph.sleepTimer.state.collectAsState()
  val settings by graph.settings.settings.collectAsState()
  val scope = rememberCoroutineScope()

  val bookId = narration.bookId ?: return

  // Recounted every twenty paragraphs rather than on every sentence spoken.
  var remaining by remember(bookId) { mutableStateOf<TextSize?>(null) }
  LaunchedEffect(bookId, narration.paragraphIndex / 20) {
    remaining = runCatching { graph.paragraphs.sizeFrom(bookId, narration.paragraphIndex) }.getOrNull()
  }

  Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp) {
    Column {
      narration.error?.let { message ->
        Surface(color = MaterialTheme.colorScheme.errorContainer) {
          Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 16.dp)) {
            Text(
              text = message,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onErrorContainer,
              modifier = Modifier.weight(1f).padding(vertical = 10.dp),
            )
          }
        }
      }

      val fraction = if (narration.paragraphCount > 0) {
        (narration.paragraphIndex.toFloat() / narration.paragraphCount).coerceIn(0f, 1f)
      } else {
        0f
      }
      LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(3.dp))

      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(84.dp).padding(horizontal = 12.dp),
      ) {
        // ---- what is playing ----
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier
            .weight(1f)
            .clickable { onOpenReader(bookId) }
            .padding(vertical = 8.dp),
        ) {
          BookCover(coverPath = narration.coverPath, modifier = Modifier.height(60.dp).aspectRatio(0.66f))
          Column(Modifier.padding(start = 12.dp)) {
            Text(
              text = narration.bookTitle,
              style = MaterialTheme.typography.titleSmall.bookText(),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = narration.chapterTitle ?: "Chapter ${narration.chapterIndex + 1}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            val pace = narration.charsPerSecondAt1x * narration.speechRate
            val left = (remaining?.characters ?: 0L) / pace.coerceAtLeast(1f)
            Text(
              text = "${(fraction * 100).toInt()}%" + if (remaining != null) " · ${duration(left)} left" else "",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }

        // ---- transport ----
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          IconButton(onClick = { scope.launch { narrator.previousChapter() } }) {
            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous chapter (Ctrl+Left)")
          }
          IconButton(onClick = { scope.launch { narrator.skipParagraphs(-settings.skipParagraphs) } }) {
            Icon(Icons.Default.FastRewind, contentDescription = "Back (Left)")
          }
          FilledIconButton(onClick = { scope.launch { narrator.togglePlayPause() } }, modifier = Modifier.size(56.dp)) {
            Box(contentAlignment = Alignment.Center) {
              if (narration.isPreparing) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
              } else {
                Icon(
                  imageVector = if (narration.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                  contentDescription = if (narration.isPlaying) "Pause (Space)" else "Play (Space)",
                  modifier = Modifier.size(30.dp),
                )
              }
            }
          }
          IconButton(onClick = { scope.launch { narrator.skipParagraphs(settings.skipParagraphs) } }) {
            Icon(Icons.Default.FastForward, contentDescription = "Forward (Right)")
          }
          IconButton(onClick = { scope.launch { narrator.nextChapter() } }) {
            Icon(Icons.Default.SkipNext, contentDescription = "Next chapter (Ctrl+Right)")
          }
        }

        // ---- speed, sleep timer, volume ----
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.End,
          modifier = Modifier.weight(1f),
        ) {
          narration.bookId?.let { bookId ->
            HistoryButton(bookId) { entry -> scope.launch { narrator.seekTo(entry.paragraphIndex, entry.charOffset) } }
          }
          SpeedButton(rate = narration.speechRate, onRate = { scope.launch { narrator.setSpeechRate(it) } })
          SleepButton(
            state = sleep,
            onStart = { minutes -> graph.sleepTimer.start(minutes * 60_000L) },
            onEndOfChapter = { graph.sleepTimer.startUntilEndOfChapter() },
            onExtend = { graph.sleepTimer.extend(10 * 60_000L) },
            onCancel = { graph.sleepTimer.cancel() },
          )
          IconButton(onClick = { narrator.setVolume(if (narration.volume > 0f) 0f else 1f) }) {
            Icon(
              imageVector = if (narration.volume > 0f) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
              contentDescription = "Volume",
            )
          }
          Slider(
            value = narration.volume,
            onValueChange = { narrator.setVolume(it) },
            modifier = Modifier.width(110.dp),
          )
        }
      }
    }
  }
}

/** The places remembered in this book: where play was pressed, the app opened, a jump made. */
@Composable
private fun HistoryButton(bookId: String, onPick: (com.akhouri.aloud.data.HistoryEntry) -> Unit) {
  var open by remember { mutableStateOf(false) }
  Box {
    IconButton(onClick = { open = true }) {
      Icon(Icons.Default.History, contentDescription = "Where you've been in this book")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.width(420.dp)) {
      Text(
        "Where you've been",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
      )
      HistoryList(bookId = bookId, limit = 12) { entry ->
        open = false
        onPick(entry)
      }
    }
  }
}

@Composable
private fun SpeedButton(rate: Float, onRate: (Float) -> Unit) {
  var open by remember { mutableStateOf(false) }
  Box {
    TextButton(onClick = { open = true }) { Text("${formatRate(rate)}×") }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      Text(
        text = "Speed",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
      )
      Slider(
        value = rate,
        onValueChange = onRate,
        valueRange = 0.5f..3.0f,
        modifier = Modifier.padding(horizontal = 12.dp).widthIn(min = 220.dp),
      )
      HorizontalDivider()
      SPEED_PRESETS.forEach { preset ->
        DropdownMenuItem(
          text = { Text("${formatRate(preset)}×" + if (kotlin.math.abs(preset - rate) < 0.01f) "  ✓" else "") },
          onClick = {
            onRate(preset)
            open = false
          },
        )
      }
    }
  }
}

@Composable
private fun SleepButton(
  state: com.akhouri.aloud.playback.SleepTimer.State,
  onStart: (Int) -> Unit,
  onEndOfChapter: () -> Unit,
  onExtend: () -> Unit,
  onCancel: () -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  Box {
    if (state.running) {
      TextButton(onClick = { open = true }) {
        Icon(Icons.Default.Bedtime, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(
          text = if (state.untilEndOfChapter) "End of chapter" else clock(state.remainingMs),
          modifier = Modifier.padding(start = 6.dp),
        )
      }
    } else {
      IconButton(onClick = { open = true }) { Icon(Icons.Default.Bedtime, contentDescription = "Sleep timer") }
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      if (state.running) {
        if (!state.untilEndOfChapter) {
          DropdownMenuItem(text = { Text("Add 10 minutes") }, onClick = { onExtend(); open = false })
        }
        DropdownMenuItem(
          text = { Text("Cancel the timer") },
          leadingIcon = { Icon(Icons.Default.Close, null) },
          onClick = { onCancel(); open = false },
        )
        HorizontalDivider()
      }
      SLEEP_PRESETS_MIN.forEach { minutes ->
        DropdownMenuItem(text = { Text("Stop in $minutes minutes") }, onClick = { onStart(minutes); open = false })
      }
      DropdownMenuItem(text = { Text("Stop at the end of the chapter") }, onClick = { onEndOfChapter(); open = false })
    }
  }
}

fun formatRate(rate: Float): String {
  val rounded = Math.round(rate * 100f) / 100f
  return if (rounded == rounded.toInt().toFloat()) {
    rounded.toInt().toString()
  } else {
    String.format(Locale.US, "%.2f", rounded).trimEnd('0').trimEnd('.')
  }
}

private fun clock(ms: Long): String {
  val total = (ms / 1000).coerceAtLeast(0)
  return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
}

private fun duration(seconds: Float): String {
  val minutes = (seconds / 60f).toLong().coerceAtLeast(0L)
  val hours = minutes / 60
  return when {
    hours > 0 -> "$hours h ${minutes % 60} min"
    minutes > 0 -> "$minutes min"
    else -> "under a minute"
  }
}

