@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package com.akhouri.aloud.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import com.akhouri.aloud.AppPaths
import com.akhouri.aloud.audio.WavPlayer
import com.akhouri.aloud.data.LibraryView
import com.akhouri.aloud.data.ThemeColorScheme
import com.akhouri.aloud.data.ThemeMode
import com.akhouri.aloud.tts.DownloadableVoice
import com.akhouri.aloud.tts.Voice
import com.akhouri.aloud.tts.Voices
import com.akhouri.aloud.ui.FilePickers
import com.akhouri.aloud.ui.IslandContentPadding
import com.akhouri.aloud.ui.IslandLabel
import com.akhouri.aloud.ui.IslandRow
import com.akhouri.aloud.ui.IslandStepperRow
import com.akhouri.aloud.ui.IslandSwitchRow
import com.akhouri.aloud.ui.LocalGraph
import com.akhouri.aloud.ui.LocalWindow
import com.akhouri.aloud.ui.SettingsIsland
import com.akhouri.aloud.ui.ShapedIcon
import com.akhouri.aloud.ui.theme.rememberThemeColorScheme
import com.akhouri.aloud.ui.theme.seedColor
import com.akhouri.aloud.ui.theme.windowsAccent
import java.awt.Desktop
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Laid out after the settings screen of Voice by Paul Woitaschek (GPLv3): one island for each
 * group of settings, each in a container colour of its own.
 */

@Composable
fun SettingsScreen(onBack: () -> Unit) {
  val graph = LocalGraph.current
  val window = LocalWindow.current
  val store = graph.settings
  val settings by store.settings.collectAsState()
  val scope = rememberCoroutineScope()
  val colors = MaterialTheme.colorScheme

  var voices by remember { mutableStateOf(Voices.installed()) }
  var pickingStart by remember { mutableStateOf(false) }
  var pickingEnd by remember { mutableStateOf(false) }
  var previewing by remember { mutableStateOf<String?>(null) }
  var voiceMessage by remember { mutableStateOf<String?>(null) }
  val downloads = remember { mutableStateMapOf<String, Float>() }

  val currentVoice = Voices.resolve(settings.voicePath)

  fun preview(voice: Voice) {
    if (previewing != null) return
    previewing = voice.id
    scope.launch {
      val dir = File(AppPaths.speech, "preview")
      val file = graph.piper.synthesize(
        "This is how this voice will read your books.",
        voice.model,
        graph.narrator.state.value.speechRate,
        dir,
      )
      if (file == null) {
        voiceMessage = "That voice could not be played" + (graph.piper.lastError?.let { ": $it" } ?: ".")
      } else {
        withContext(Dispatchers.IO) {
          val player = WavPlayer()
          player.play(file) { false }
          player.drain { false }
          player.close()
          file.delete()
        }
      }
      previewing = null
    }
  }

  fun download(entry: DownloadableVoice) {
    if (entry.id in downloads) return
    downloads[entry.id] = 0f
    scope.launch {
      val result = Voices.download(entry) { progress -> scope.launch { downloads[entry.id] = progress } }
      downloads.remove(entry.id)
      voices = Voices.installed()
      voiceMessage = result.fold(
        onSuccess = { "${it.displayName} is ready" },
        onFailure = { "The download failed: ${it.message ?: it.javaClass.simpleName}" },
      )
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Settings", style = MaterialTheme.typography.titleLargeEmphasized) },
        navigationIcon = {
          IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back (Esc)") }
        },
      )
    },
  ) { padding ->
    Box(Modifier.padding(padding).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
      Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
          .widthIn(max = 820.dp)
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 16.dp)
          .padding(bottom = 32.dp),
      ) {

        // ---- Appearance -------------------------------------------------------------------
        SettingsIsland(title = "Appearance", containerColor = colors.primaryContainer) {
          IslandLabel("Theme")
          Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
            ThemeMode.entries.forEach { mode ->
              ThemeModeTile(
                mode = mode,
                scheme = settings.themeColorScheme,
                pureBlack = settings.pureBlack,
                selected = settings.themeMode == mode,
                onClick = { store.update { it.copy(themeMode = mode) } },
                modifier = Modifier.weight(1f).widthIn(max = 160.dp),
              )
            }
          }
          AnimatedVisibility(visible = settings.themeMode != ThemeMode.Light) {
            IslandSwitchRow(
              title = "Pure black",
              summary = "A true black background in the dark theme",
              checked = settings.pureBlack,
              onCheckedChange = { on -> store.update { it.copy(pureBlack = on) } },
              leading = { LeadIcon(Icons.Default.Contrast, MaterialShapes.Cookie12Sided, colors.primary, colors.onPrimary) },
              modifier = Modifier.padding(top = 12.dp),
            )
          }
          IslandLabel("Colours", modifier = Modifier.padding(top = 20.dp))
          Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
          ) {
            ThemeColorScheme.entries
              .filter { it != ThemeColorScheme.Accent || windowsAccent != null }
              .forEach { scheme ->
                PaletteSwatch(
                  scheme = scheme,
                  selected = scheme == settings.themeColorScheme,
                  onClick = { store.update { it.copy(themeColorScheme = scheme) } },
                )
              }
          }
          Text(
            text = schemeLabel(settings.themeColorScheme),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = IslandContentPadding).padding(top = 12.dp),
          )
        }

        // ---- Voice --------------------------------------------------------------------------
        SettingsIsland(title = "Voice", containerColor = colors.tertiaryContainer) {
          if (!graph.piper.isInstalled) {
            Text(
              text = "The speech engine is missing from this installation. Reinstalling Aloud should fix it.",
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.padding(horizontal = IslandContentPadding),
            )
          }
          IslandLabel("On this PC")
          voices.forEach { voice ->
            IslandRow(
              title = voice.displayName,
              summary = if (voice.bundled) "Comes with Aloud" else "Added",
              onClick = {
                store.update { it.copy(voicePath = voice.model.absolutePath) }
              },
              leading = { RadioButton(selected = currentVoice?.id == voice.id, onClick = null) },
              trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  if (previewing == voice.id) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp).padding(2.dp))
                  } else {
                    IconButton(onClick = { preview(voice) }) {
                      Icon(Icons.Default.PlayArrow, contentDescription = "Hear ${voice.displayName}")
                    }
                  }
                  if (!voice.bundled) {
                    IconButton(onClick = {
                      Voices.remove(voice)
                      voices = Voices.installed()
                      if (currentVoice?.id == voice.id) store.update { it.copy(voicePath = null) }
                    }) {
                      Icon(Icons.Default.Delete, contentDescription = "Remove ${voice.displayName}")
                    }
                  }
                }
              },
            )
          }

          IslandLabel("More voices to download", modifier = Modifier.padding(top = 16.dp))
          Voices.catalogue.filter { entry -> voices.none { it.id == entry.id } }.forEach { entry ->
            val progress = downloads[entry.id]
            IslandRow(
              title = Voices.describe(entry.id),
              summary = entry.note,
              onClick = { download(entry) },
              leading = { LeadIcon(Icons.Default.Download, MaterialShapes.Pill, colors.tertiary, colors.onTertiary) },
              trailing = {
                if (progress != null) {
                  LinearProgressIndicator(progress = { progress }, modifier = Modifier.size(width = 80.dp, height = 4.dp))
                }
              },
            )
          }
          IslandRow(
            title = "Add a voice from a file",
            summary = "Any Piper voice: the .onnx file, with its .onnx.json beside it",
            onClick = {
              FilePickers.openVoice(window)?.let { file ->
                voiceMessage = Voices.addFromFile(file).fold(
                  onSuccess = { "${it.displayName} added" },
                  onFailure = { it.message ?: "That voice could not be added" },
                )
                voices = Voices.installed()
              }
            },
            leading = { LeadIcon(Icons.Default.UploadFile, MaterialShapes.Cookie9Sided, colors.tertiary, colors.onTertiary) },
          )
          voiceMessage?.let {
            Text(
              text = it,
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.padding(horizontal = IslandContentPadding, vertical = 8.dp),
            )
          }
        }

        // ---- Listening ----------------------------------------------------------------------
        SettingsIsland(title = "Listening", containerColor = colors.secondaryContainer) {
          IslandStepperRow(
            title = "Skip amount",
            summary = "Paragraphs the back and forward buttons and arrow keys move by",
            value = settings.skipParagraphs.toString(),
            onDecrease = { store.update { it.copy(skipParagraphs = (it.skipParagraphs - 1).coerceAtLeast(1)) } },
            onIncrease = { store.update { it.copy(skipParagraphs = (it.skipParagraphs + 1).coerceAtMost(20)) } },
            leading = { LeadIcon(Icons.Default.FastForward, MaterialShapes.Cookie9Sided, colors.secondary, colors.onSecondary) },
          )
          IslandStepperRow(
            title = "Rewind on resume",
            summary = "Paragraphs to step back when picking up after a pause",
            value = settings.rewindOnResume.toString(),
            onDecrease = { store.update { it.copy(rewindOnResume = (it.rewindOnResume - 1).coerceAtLeast(0)) } },
            onIncrease = { store.update { it.copy(rewindOnResume = (it.rewindOnResume + 1).coerceAtMost(5)) } },
            leading = { LeadIcon(Icons.Default.Replay, MaterialShapes.Sunny, colors.secondary, colors.onSecondary) },
          )
          IslandStepperRow(
            title = "Reader text size",
            summary = "Also Ctrl and + or − while reading",
            value = settings.readerTextSize.toString(),
            onDecrease = { store.update { it.copy(readerTextSize = (it.readerTextSize - 1).coerceAtLeast(14)) } },
            onIncrease = { store.update { it.copy(readerTextSize = (it.readerTextSize + 1).coerceAtMost(36)) } },
            leading = { LeadIcon(Icons.Default.TextFields, MaterialShapes.Square, colors.secondary, colors.onSecondary) },
          )
        }

        // ---- Windows ------------------------------------------------------------------------
        SettingsIsland(title = "Windows", containerColor = colors.surfaceContainerHigh) {
          IslandSwitchRow(
            title = "Media controls",
            summary = "Show Aloud in Windows' media flyout and answer the keyboard's media keys " +
              "and Bluetooth headset buttons",
            checked = settings.mediaControls,
            onCheckedChange = { on -> store.update { it.copy(mediaControls = on) } },
            leading = { LeadIcon(Icons.Default.Keyboard, MaterialShapes.Pill, colors.primary, colors.onPrimary) },
          )
          IslandSwitchRow(
            title = "Keep running in the notification area",
            summary = "Closing the window keeps narration going; quit from Aloud's icon by the clock",
            checked = settings.closeToTray,
            onCheckedChange = { on -> store.update { it.copy(closeToTray = on) } },
            leading = { LeadIcon(Icons.Default.Minimize, MaterialShapes.Clover4Leaf, colors.primary, colors.onPrimary) },
          )
          IslandSwitchRow(
            title = "Show books as a grid",
            summary = "Covers side by side instead of a list",
            checked = settings.libraryView == LibraryView.Grid,
            onCheckedChange = { grid ->
              store.update { it.copy(libraryView = if (grid) LibraryView.Grid else LibraryView.List) }
            },
            leading = { LeadIcon(Icons.Default.GridView, MaterialShapes.Clover4Leaf, colors.primary, colors.onPrimary) },
          )
          IslandRow(
            title = "Open Aloud's data folder",
            summary = AppPaths.dataDir.absolutePath,
            onClick = { runCatching { Desktop.getDesktop().open(AppPaths.dataDir) } },
            leading = { LeadIcon(Icons.Default.FolderOpen, MaterialShapes.Square, colors.primary, colors.onPrimary) },
          )
        }

        // ---- Sleep timer --------------------------------------------------------------------
        SettingsIsland(title = "Sleep timer", containerColor = colors.surfaceContainer) {
          IslandStepperRow(
            title = "Timer length",
            summary = "Minutes the automatic timer runs for",
            value = settings.sleepMinutes.toString(),
            onDecrease = { store.update { it.copy(sleepMinutes = (it.sleepMinutes - 5).coerceAtLeast(5)) } },
            onIncrease = { store.update { it.copy(sleepMinutes = (it.sleepMinutes + 5).coerceAtMost(180)) } },
            leading = { LeadIcon(Icons.Default.Timer, MaterialShapes.Cookie6Sided, colors.primary, colors.onPrimary) },
          )
          IslandSwitchRow(
            title = "Start it automatically",
            summary = "When narration begins between the hours below",
            checked = settings.autoSleep,
            onCheckedChange = { on -> store.update { it.copy(autoSleep = on) } },
            leading = { LeadIcon(Icons.Default.Bedtime, MaterialShapes.Cookie12Sided, colors.primary, colors.onPrimary) },
          )
          AnimatedVisibility(visible = settings.autoSleep) {
            Column {
              IslandRow(
                title = "From",
                summary = clock(settings.autoSleepStartMinute),
                onClick = { pickingStart = true },
                leading = { LeadIcon(Icons.Default.Schedule, MaterialShapes.Pill, colors.secondary, colors.onSecondary) },
              )
              IslandRow(
                title = "Until",
                summary = clock(settings.autoSleepEndMinute),
                onClick = { pickingEnd = true },
                leading = { LeadIcon(Icons.Default.Schedule, MaterialShapes.Pill, colors.secondary, colors.onSecondary) },
              )
            }
          }
        }

        // ---- Shortcuts ----------------------------------------------------------------------
        SettingsIsland(title = "Keyboard shortcuts", containerColor = colors.surfaceContainerLow) {
          SHORTCUTS.forEach { (keys, action) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = IslandContentPadding, vertical = 6.dp)) {
              Text(keys, style = MaterialTheme.typography.labelLarge, modifier = Modifier.widthIn(min = 170.dp))
              Text(action, style = MaterialTheme.typography.bodyMedium)
            }
          }
        }

        Text(
          text = "Aloud ${AppPaths.version}\nReads EPUB, PDF with a text layer, Kindle books without DRM, " +
            "FB2, Word, HTML and plain text. Everything is converted to text and read aloud on this PC " +
            "by Piper, an offline neural voice: no account, no server, no connection needed.\n" +
            "Design after Voice by Paul Woitaschek.",
          style = MaterialTheme.typography.bodySmall,
          color = colors.onSurfaceVariant,
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
      }
    }
  }

  if (pickingStart) {
    TimeDialog(settings.autoSleepStartMinute, onDismiss = { pickingStart = false }) { minute ->
      pickingStart = false
      store.update { it.copy(autoSleepStartMinute = minute) }
    }
  }
  if (pickingEnd) {
    TimeDialog(settings.autoSleepEndMinute, onDismiss = { pickingEnd = false }) { minute ->
      pickingEnd = false
      store.update { it.copy(autoSleepEndMinute = minute) }
    }
  }
}

private val SHORTCUTS = listOf(
  "Space" to "Play or pause",
  "Left / Right" to "Back or forward by the skip amount",
  "Ctrl + Left / Right" to "Previous or next chapter",
  "Ctrl + Up / Down" to "Faster or slower",
  "Ctrl + F" to "Search",
  "Ctrl + O" to "Add books",
  "Ctrl + B" to "Bookmark the current place",
  "Ctrl + + / −" to "Larger or smaller reader text",
  "Ctrl + ," to "Settings",
  "Esc / Alt + Left" to "Back",
)

@Composable
private fun LeadIcon(icon: ImageVector, shape: RoundedPolygon, container: Color, content: Color) {
  ShapedIcon(icon = icon, shape = shape, containerColor = container, contentColor = content)
}

private fun clock(minuteOfDay: Int): String =
  String.format(Locale.US, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60)

private fun schemeLabel(scheme: ThemeColorScheme): String = when (scheme) {
  ThemeColorScheme.Blue -> "Aloud blue"
  ThemeColorScheme.Accent -> "Your Windows accent colour"
  ThemeColorScheme.Lagoon -> "Lagoon"
  ThemeColorScheme.Forest -> "Forest"
  ThemeColorScheme.Sunset -> "Sunset"
  ThemeColorScheme.Berry -> "Berry"
  ThemeColorScheme.Lavender -> "Lavender"
}

@Composable
private fun TimeDialog(initialMinute: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
  val state = rememberTimePickerState(initialHour = initialMinute / 60, initialMinute = initialMinute % 60, is24Hour = true)
  AlertDialog(
    onDismissRequest = onDismiss,
    text = { TimePicker(state = state) },
    confirmButton = { TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text("Set") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

/** Light, dark or follow Windows, shown as a miniature of the app in those colours. */
@Composable
private fun ThemeModeTile(
  mode: ThemeMode,
  scheme: ThemeColorScheme,
  pureBlack: Boolean,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val light = rememberThemeColorScheme(scheme, dark = false)
  val dark = rememberThemeColorScheme(scheme, dark = true, pureBlack = pureBlack)
  val shape = RoundedCornerShape(20.dp)
  val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = modifier.clip(RoundedCornerShape(16.dp)).selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
  ) {
    Canvas(
      Modifier.fillMaxWidth().aspectRatio(0.75f).clip(shape).border(if (selected) 3.dp else 1.dp, borderColor, shape),
    ) {
      when (mode) {
        ThemeMode.Light -> drawMiniScreen(light)
        ThemeMode.Dark -> drawMiniScreen(dark)
        ThemeMode.FollowSystem -> {
          drawMiniScreen(light)
          val split = Path().apply {
            moveTo(size.width, 0f)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
          }
          clipPath(split) { drawMiniScreen(dark) }
        }
      }
    }
    Text(
      text = when (mode) {
        ThemeMode.FollowSystem -> "Windows"
        ThemeMode.Light -> "Light"
        ThemeMode.Dark -> "Dark"
      },
      style = MaterialTheme.typography.labelLarge,
      color = LocalContentColor.current.copy(alpha = if (selected) 1f else 0.7f),
      modifier = Modifier.padding(top = 8.dp),
    )
  }
}

/** A cover, a title, a progress bar and a play button: just enough to look like the app. */
private fun DrawScope.drawMiniScreen(colors: ColorScheme) {
  drawRect(colors.surface)
  val cover = size.width * 0.5f
  val coverTop = size.height * 0.1f
  drawRoundRect(colors.primaryContainer, Offset((size.width - cover) / 2, coverTop), Size(cover, cover), CornerRadius(cover * 0.18f))
  val line = size.height * 0.035f
  val titleTop = coverTop + cover + size.height * 0.05f
  drawRoundRect(colors.onSurface, Offset(size.width * 0.24f, titleTop), Size(size.width * 0.52f, line), CornerRadius(line / 2))
  drawRoundRect(colors.onSurfaceVariant, Offset(size.width * 0.32f, titleTop + line * 1.8f), Size(size.width * 0.36f, line), CornerRadius(line / 2), alpha = 0.6f)
  val barTop = titleTop + line * 4.6f
  drawRoundRect(colors.primary.copy(alpha = 0.25f), Offset(size.width * 0.16f, barTop), Size(size.width * 0.68f, line * 0.8f), CornerRadius(line / 2))
  drawRoundRect(colors.primary, Offset(size.width * 0.16f, barTop), Size(size.width * 0.4f, line * 0.8f), CornerRadius(line / 2))
  drawCircle(colors.primary, radius = size.width * 0.1f, center = Offset(size.width / 2, size.height * 0.87f))
}

@Composable
private fun PaletteSwatch(scheme: ThemeColorScheme, selected: Boolean, onClick: () -> Unit) {
  val swatch = seedColor(scheme)
  Box(
    contentAlignment = Alignment.Center,
    modifier = Modifier
      .size(52.dp)
      .clip(CircleShape)
      .background(swatch)
      .border(
        width = if (selected) 3.dp else 0.dp,
        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else swatch,
        shape = CircleShape,
      )
      .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
  ) {
    if (selected) Icon(Icons.Default.Check, contentDescription = schemeLabel(scheme), tint = Color.White)
  }
}

