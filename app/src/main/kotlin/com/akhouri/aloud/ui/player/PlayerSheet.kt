package com.akhouri.aloud.ui.player

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.akhouri.aloud.data.Settings
import com.akhouri.aloud.graph
import java.util.Locale
import kotlinx.coroutines.launch

/** Speeds offered as one tap, with the fine slider underneath for anything between. */
private val SPEED_PRESETS =
  listOf(0.5f, 0.75f, 1.0f, 1.1f, 1.25f, 1.5f, 1.75f, 2.0f, 2.25f, 2.5f, 3.0f)

private val SLEEP_PRESETS_MIN = listOf(5, 10, 15, 30, 45, 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSheet(onDismiss: () -> Unit) {
  val context = LocalContext.current
  val appGraph = context.graph
  val narrator = appGraph.narrator
  val sleepTimer = appGraph.sleepTimer
  val scope = rememberCoroutineScope()

  val state by narrator.state.collectAsStateWithLifecycle()
  val sleep by sleepTimer.state.collectAsStateWithLifecycle()
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val saved by remember { appGraph.settings.settings }
    .collectAsStateWithLifecycle(initialValue = Settings())
  val skip = saved.skipParagraphs

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {

      Text(
        text = state.bookTitle,
        style = MaterialTheme.typography.titleLarge,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = state.chapterTitle ?: "Chapter ${state.chapterIndex + 1}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      val fraction = if (state.paragraphCount > 0) {
        state.paragraphIndex.toFloat() / state.paragraphCount
      } else {
        0f
      }
      LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
      )
      Text(
        text = "Paragraph ${state.paragraphIndex + 1} of ${state.paragraphCount}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
      )

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
      ) {
        IconButton(onClick = { scope.launch { narrator.previousChapter() } }) {
          Icon(Icons.Default.SkipPrevious, contentDescription = "Previous chapter")
        }
        IconButton(onClick = { scope.launch { narrator.skipParagraphs(-skip) } }) {
          Icon(Icons.Default.Replay30, contentDescription = "Back a paragraph")
        }
        FilledIconButton(
          onClick = { scope.launch { narrator.togglePlayPause() } },
          modifier = Modifier.size(72.dp),
        ) {
          Icon(
            imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = if (state.isPlaying) "Pause" else "Play",
            modifier = Modifier.size(36.dp),
          )
        }
        IconButton(onClick = { scope.launch { narrator.skipParagraphs(skip) } }) {
          Icon(Icons.Default.Forward30, contentDescription = "Forward a paragraph")
        }
        IconButton(onClick = { scope.launch { narrator.nextChapter() } }) {
          Icon(Icons.Default.SkipNext, contentDescription = "Next chapter")
        }
      }

      SectionLabel("Speed — ${formatRate(state.speechRate)}×")
      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
      ) {
        SPEED_PRESETS.forEach { preset ->
          FilterChip(
            selected = kotlin.math.abs(state.speechRate - preset) < 0.01f,
            onClick = {
              scope.launch {
                narrator.setSpeechRate(preset)
                appGraph.settings.setSpeechRate(preset)
              }
            },
            label = { Text("${formatRate(preset)}×") },
          )
        }
      }
      Slider(
        value = state.speechRate,
        onValueChange = { scope.launch { narrator.setSpeechRate(it) } },
        onValueChangeFinished = {
          scope.launch { appGraph.settings.setSpeechRate(narrator.state.value.speechRate) }
        },
        valueRange = 0.5f..3.0f,
        modifier = Modifier.fillMaxWidth(),
      )

      SectionLabel("Pitch — ${formatRate(state.pitch)}×")
      Slider(
        value = state.pitch,
        onValueChange = { scope.launch { narrator.setPitch(it) } },
        onValueChangeFinished = {
          scope.launch { appGraph.settings.setPitch(narrator.state.value.pitch) }
        },
        valueRange = 0.5f..2.0f,
        modifier = Modifier.fillMaxWidth(),
      )

      SectionLabel("Sleep timer")
      if (sleep.running) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Default.Bedtime, contentDescription = null)
          Text(
            text = if (sleep.untilEndOfChapter) {
              "Stops at the end of this chapter"
            } else {
              "Stops in ${formatRemaining(sleep.remainingMs)}"
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
          )
          AssistChip(onClick = { sleepTimer.cancel() }, label = { Text("Cancel") })
        }
      } else {
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        ) {
          SLEEP_PRESETS_MIN.forEach { minutes ->
            AssistChip(
              onClick = { sleepTimer.start(minutes * 60_000L) },
              label = { Text("$minutes min") },
            )
          }
          AssistChip(
            onClick = { sleepTimer.startUntilEndOfChapter() },
            label = { Text("End of chapter") },
          )
        }
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
    textAlign = TextAlign.Start,
    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
  )
}

internal fun formatRate(rate: Float): String {
  val rounded = Math.round(rate * 100f) / 100f
  return if (rounded == rounded.toInt().toFloat()) {
    rounded.toInt().toString()
  } else {
    String.format(Locale.US, "%.2f", rounded).trimEnd('0').trimEnd('.')
  }
}

private fun formatRemaining(ms: Long): String {
  val totalSeconds = (ms / 1000).coerceAtLeast(0)
  val minutes = totalSeconds / 60
  val seconds = totalSeconds % 60
  return String.format(Locale.US, "%d:%02d", minutes, seconds)
}
