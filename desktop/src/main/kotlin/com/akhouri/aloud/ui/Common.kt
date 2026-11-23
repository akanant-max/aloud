package com.akhouri.aloud.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decoded covers, kept small and kept around, so scrolling the library never re-reads a file. */
private object CoverCache {
  private const val MAX_ENTRIES = 300
  private const val MAX_HEIGHT = 480

  private val entries = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > MAX_ENTRIES
  }

  @Synchronized
  fun cached(path: String): ImageBitmap? = entries[path]

  fun load(path: String): ImageBitmap? {
    cached(path)?.let { return it }
    val image = runCatching { ImageIO.read(File(path)) }.getOrNull() ?: return null
    val scaled = if (image.height > MAX_HEIGHT) {
      val width = (image.width.toFloat() * MAX_HEIGHT / image.height).toInt().coerceAtLeast(1)
      BufferedImage(width, MAX_HEIGHT, BufferedImage.TYPE_INT_RGB).also { target ->
        val g = target.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(image, 0, 0, width, MAX_HEIGHT, null)
        g.dispose()
      }
    } else {
      image
    }
    val bitmap = scaled.toComposeImageBitmap()
    synchronized(this) { entries[path] = bitmap }
    return bitmap
  }
}

/** Cover art, falling back to a tinted book glyph when the book has none. */
@Composable
fun BookCover(coverPath: String?, modifier: Modifier = Modifier) {
  val shape = RoundedCornerShape(8.dp)
  val bitmap by produceState(initialValue = coverPath?.let(CoverCache::cached), coverPath) {
    value = coverPath?.let { path -> withContext(Dispatchers.IO) { CoverCache.load(path) } }
  }
  val image = bitmap
  if (image != null) {
    Image(
      bitmap = image,
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier = modifier.clip(shape),
    )
  } else {
    Box(
      modifier = modifier.clip(shape).background(MaterialTheme.colorScheme.secondaryContainer),
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

/** Centred message for an empty list or an error, rather than a blank screen. */
@Composable
fun EmptyState(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier.fillMaxSize().padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    if (subtitle != null) {
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp),
      )
    }
  }
}

/**
 * The mouse conventions of a desktop list: a right-click opens the item's menu, and a Ctrl- or
 * Shift-click adds it to the selection instead of opening it.
 */
@Composable
fun Modifier.desktopClicks(onSecondary: () -> Unit, onModifiedPrimary: (() -> Unit)? = null): Modifier {
  val secondary by rememberUpdatedState(onSecondary)
  val modified by rememberUpdatedState(onModifiedPrimary)
  return pointerInput(Unit) {
    awaitPointerEventScope {
      while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        if (event.type != PointerEventType.Press) continue
        val withModifier = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isShiftPressed
        when {
          event.buttons.isSecondaryPressed -> {
            secondary()
            event.changes.forEach { it.consume() }
          }
          event.buttons.isPrimaryPressed && withModifier && modified != null -> {
            modified?.invoke()
            event.changes.forEach { it.consume() }
          }
        }
      }
    }
  }
}
