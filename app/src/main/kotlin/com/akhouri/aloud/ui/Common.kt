package com.akhouri.aloud.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.akhouri.aloud.graph
import kotlinx.coroutines.launch

/** Cover art, falling back to a tinted book glyph when the file had none. */
@Composable
fun BookCover(coverPath: String?, modifier: Modifier = Modifier) {
  val shape = RoundedCornerShape(8.dp)
  if (coverPath != null) {
    AsyncImage(
      model = coverPath,
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier = modifier.clip(shape),
    )
  } else {
    Box(
      modifier = modifier
        .clip(shape)
        .background(MaterialTheme.colorScheme.secondaryContainer),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.AutoMirrored.Filled.MenuBook,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.size(24.dp),
      )
    }
  }
}

/**
 * The persistent bar showing what is being read. Appears once a book is loaded and stays put
 * across screens, so play and pause are always one tap away.
 */
@Composable
fun NowPlayingBar(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val narrator = context.graph.narrator
  val state by narrator.state.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()

  if (!state.hasBook) return

  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    modifier = modifier.fillMaxWidth(),
  ) {
    Column {
      val progress = if (state.paragraphCount > 0) {
        state.paragraphIndex.toFloat() / state.paragraphCount
      } else {
        0f
      }
      LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth(),
      )
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
          .clickable(onClick = onClick)
          .padding(horizontal = 12.dp, vertical = 8.dp),
      ) {
        Column(Modifier.weight(1f)) {
          Text(
            text = state.bookTitle,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = state.chapterTitle ?: "Chapter ${state.chapterIndex + 1}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        IconButton(onClick = { scope.launch { narrator.togglePlayPause() } }) {
          Icon(
            imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = if (state.isPlaying) "Pause" else "Play",
          )
        }
      }
    }
  }
}

/** Centred message for an empty list or an error, rather than a blank screen. */
@Composable
fun EmptyState(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier.fillMaxSize().padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (subtitle != null) {
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
      )
    }
  }
}
