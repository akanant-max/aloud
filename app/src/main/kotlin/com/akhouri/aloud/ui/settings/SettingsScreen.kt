@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package com.akhouri.aloud.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.akhouri.aloud.data.LibraryView
import com.akhouri.aloud.data.Settings
import com.akhouri.aloud.data.ThemeColorScheme
import com.akhouri.aloud.data.ThemeMode
import com.akhouri.aloud.graph
import com.akhouri.aloud.tts.EngineInfo
import com.akhouri.aloud.ui.IslandContentPadding
import com.akhouri.aloud.ui.IslandLabel
import com.akhouri.aloud.ui.IslandRow
import com.akhouri.aloud.ui.IslandStepperRow
import com.akhouri.aloud.ui.IslandSwitchRow
import com.akhouri.aloud.ui.SettingsIsland
import com.akhouri.aloud.ui.ShapedIcon
import com.akhouri.aloud.ui.theme.dynamicColorAvailable
import com.akhouri.aloud.ui.theme.rememberThemeColorScheme
import com.akhouri.aloud.ui.theme.seedColor
import com.akhouri.aloud.util.SessionLog
import java.util.Locale
import kotlinx.coroutines.launch

/*
 * Laid out after the settings screen of Voice by Paul Woitaschek (GPLv3): one island for each
 * group of settings, each in a container colour of its own.
 */

@Composable
fun SettingsScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val appGraph = context.graph
  val store = appGraph.settings
  val scope = rememberCoroutineScope()

  val settings by remember { store.settings }.collectAsStateWithLifecycle(initialValue = Settings())
  var engines by remember { mutableStateOf<List<EngineInfo>>(emptyList()) }
  var voices by remember { mutableStateOf<List<String>>(emptyList()) }
  var voicesOpen by remember { mutableStateOf(false) }
  var pickingStart by remember { mutableStateOf(false) }
  var pickingEnd by remember { mutableStateOf(false) }
  var showingLog by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) { engines = appGraph.ttsEngine.availableEngines() }

  // Voices can only be listed once an engine is actually running.
  LaunchedEffect(settings.enginePackage) {
    appGraph.ttsEngine.ensureReady(settings.enginePackage)
    voices = appGraph.ttsEngine.voices().map { it.name }.take(60)
    if (engines.isEmpty()) engines = appGraph.ttsEngine.availableEngines()
  }

  val colors = MaterialTheme.colorScheme

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Settings", style = MaterialTheme.typography.titleLargeEmphasized) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
      )
    },
  ) { padding ->
    Column(
      verticalArrangement = Arrangement.spacedBy(16.dp),
      modifier = Modifier
        .padding(padding)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp)
        .padding(bottom = 32.dp),
    ) {

      // ---- Appearance ---------------------------------------------------------------------
      val shownScheme =
        if (settings.themeColorScheme == ThemeColorScheme.Dynamic && !dynamicColorAvailable) {
          ThemeColorScheme.Blue
        } else {
          settings.themeColorScheme
        }

      SettingsIsland(title = "Appearance", containerColor = colors.primaryContainer) {
        IslandLabel("Theme")
        Row(
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          modifier = Modifier.padding(horizontal = 16.dp),
        ) {
          ThemeMode.entries.forEach { mode ->
            ThemeModeTile(
              mode = mode,
              scheme = shownScheme,
              pureBlack = settings.pureBlack,
              selected = settings.themeMode == mode,
              onClick = { scope.launch { store.setThemeMode(mode) } },
              modifier = Modifier.weight(1f),
            )
          }
        }
        // It only changes the dark theme.
        AnimatedVisibility(visible = settings.themeMode != ThemeMode.Light) {
          IslandSwitchRow(
            title = "Pure black",
            summary = "A true black background in the dark theme, kind to OLED screens",
            checked = settings.pureBlack,
            onCheckedChange = { scope.launch { store.setPureBlack(it) } },
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
            .filter { it != ThemeColorScheme.Dynamic || dynamicColorAvailable }
            .forEach { scheme ->
              PaletteSwatch(
                scheme = scheme,
                selected = scheme == shownScheme,
                onClick = { scope.launch { store.setThemeColorScheme(scheme) } },
              )
            }
        }
        Text(
          text = schemeLabel(shownScheme),
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(horizontal = IslandContentPadding).padding(top = 12.dp),
        )
      }

      // ---- Library ------------------------------------------------------------------------
      SettingsIsland(title = "Library", containerColor = colors.secondaryContainer) {
        IslandSwitchRow(
          title = "Show books as a grid",
          summary = "Covers side by side instead of a list",
          checked = settings.libraryView == LibraryView.Grid,
          onCheckedChange = { grid ->
            scope.launch { store.setLibraryView(if (grid) LibraryView.Grid else LibraryView.List) }
          },
          leading = { LeadIcon(Icons.Default.GridView, MaterialShapes.Clover4Leaf, colors.secondary, colors.onSecondary) },
        )
      }

      // ---- Listening ----------------------------------------------------------------------
      SettingsIsland(title = "Listening", containerColor = colors.tertiaryContainer) {
        IslandStepperRow(
          title = "Skip amount",
          summary = "Paragraphs the back and forward buttons move by",
          value = settings.skipParagraphs.toString(),
          onDecrease = { scope.launch { store.setSkipParagraphs(settings.skipParagraphs - 1) } },
          onIncrease = { scope.launch { store.setSkipParagraphs(settings.skipParagraphs + 1) } },
          leading = { LeadIcon(Icons.Default.FastForward, MaterialShapes.Cookie9Sided, colors.tertiary, colors.onTertiary) },
        )
        IslandStepperRow(
          title = "Rewind on resume",
          summary = "Paragraphs to step back when picking up after a pause",
          value = settings.rewindOnResume.toString(),
          onDecrease = { scope.launch { store.setRewindOnResume(settings.rewindOnResume - 1) } },
          onIncrease = { scope.launch { store.setRewindOnResume(settings.rewindOnResume + 1) } },
          leading = { LeadIcon(Icons.Default.Replay, MaterialShapes.Sunny, colors.tertiary, colors.onTertiary) },
        )
        IslandSwitchRow(
          title = "Playback notification",
          summary = "Off also removes the lock-screen and headset controls, and Android may " +
            "then stop narration in the background",
          checked = settings.mediaNotification,
          onCheckedChange = { scope.launch { store.setMediaNotification(it) } },
          leading = { LeadIcon(Icons.Default.Notifications, MaterialShapes.Pill, colors.tertiary, colors.onTertiary) },
        )
      }

      // ---- Voice --------------------------------------------------------------------------
      SettingsIsland(title = "Voice", containerColor = colors.surfaceContainerHigh) {
        if (engines.isEmpty()) {
          Text(
            text = "No speech engine was found. Install Google Text-to-Speech from the Play " +
              "Store, then come back here.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = IslandContentPadding),
          )
        } else {
          IslandLabel("Speech engine")
        }
        engines.forEach { engine ->
          val selected = settings.enginePackage == engine.packageName ||
            (settings.enginePackage == null && engine.packageName == DEFAULT_ENGINE)
          IslandRow(
            title = engine.label,
            summary = engine.packageName,
            onClick = {
              scope.launch {
                store.setEngine(engine.packageName)
                store.setVoice(null)
                appGraph.narrator.useVoice(engine.packageName, null)
              }
            },
            leading = { RadioButton(selected = selected, onClick = null) },
          )
        }

        if (voices.isNotEmpty()) {
          // Folded by default: an engine can offer dozens of voices.
          IslandRow(
            title = "Voice",
            summary = settings.voiceName ?: "The engine's default",
            onClick = { voicesOpen = !voicesOpen },
            leading = { LeadIcon(Icons.Default.RecordVoiceOver, MaterialShapes.Flower, colors.primary, colors.onPrimary) },
            trailing = {
              Icon(
                imageVector = if (voicesOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (voicesOpen) "Hide the voices" else "Show the voices",
              )
            },
          )
          AnimatedVisibility(visible = voicesOpen) {
            Column {
              Text(
                text = "Only offline voices are listed, so narration works without a connection.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = IslandContentPadding, vertical = 4.dp),
              )
              voices.forEach { voice ->
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .clickable {
                      scope.launch {
                        store.setVoice(voice)
                        appGraph.narrator.useVoice(settings.enginePackage, voice)
                      }
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                  RadioButton(selected = settings.voiceName == voice, onClick = null)
                  Text(
                    text = voice,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 16.dp),
                  )
                }
              }
            }
          }
        }
      }

      // ---- Sleep timer --------------------------------------------------------------------
      SettingsIsland(title = "Sleep timer", containerColor = colors.surfaceContainer) {
        IslandStepperRow(
          title = "Timer length",
          summary = "Minutes the automatic timer runs for",
          value = settings.sleepMinutes.toString(),
          onDecrease = { scope.launch { store.setSleepMinutes(settings.sleepMinutes - 5) } },
          onIncrease = { scope.launch { store.setSleepMinutes(settings.sleepMinutes + 5) } },
          leading = { LeadIcon(Icons.Default.Timer, MaterialShapes.Cookie6Sided, colors.primary, colors.onPrimary) },
        )
        IslandSwitchRow(
          title = "Start it automatically",
          summary = "When narration begins between the hours below",
          checked = settings.autoSleep,
          onCheckedChange = { scope.launch { store.setAutoSleep(it) } },
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

      // ---- Troubleshooting ----------------------------------------------------------------
      SettingsIsland(title = "Troubleshooting", containerColor = colors.surfaceContainerLow) {
        IslandRow(
          title = "Media controls log",
          summary = "What the notification, headset or watch has sent to the app",
          onClick = { showingLog = true },
          leading = { LeadIcon(Icons.Default.Schedule, MaterialShapes.Square, colors.secondary, colors.onSecondary) },
        )
      }

      // ---- About --------------------------------------------------------------------------
      val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
          .getOrNull().orEmpty()
      }
      Text(
        text = "Aloud $version\nReads EPUB, PDF with a text layer, Kindle books without DRM, " +
          "FB2, Word, HTML and plain text. Everything is converted to text and read on this " +
          "device: no account, no server, no connection needed once a voice is installed.\n" +
          "Design after Voice by Paul Woitaschek.",
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
      )
    }
  }

  if (showingLog) {
    val log = remember { SessionLog.text() }
    AlertDialog(
      onDismissRequest = { showingLog = false },
      title = { Text("Media controls log") },
      text = {
        Text(
          text = log,
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.verticalScroll(rememberScrollState()),
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("Aloud media log", log))
            showingLog = false
          },
        ) { Text("Copy") }
      },
      dismissButton = {
        TextButton(
          onClick = {
            SessionLog.clear()
            showingLog = false
          },
        ) { Text("Clear") }
      },
    )
  }

  if (pickingStart) {
    TimeDialog(
      initialMinute = settings.autoSleepStartMinute,
      onDismiss = { pickingStart = false },
      onPick = { minute ->
        pickingStart = false
        scope.launch { store.setAutoSleepStart(minute) }
      },
    )
  }
  if (pickingEnd) {
    TimeDialog(
      initialMinute = settings.autoSleepEndMinute,
      onDismiss = { pickingEnd = false },
      onPick = { minute ->
        pickingEnd = false
        scope.launch { store.setAutoSleepEnd(minute) }
      },
    )
  }
}

@Composable
private fun LeadIcon(
  icon: ImageVector,
  shape: RoundedPolygon,
  container: androidx.compose.ui.graphics.Color,
  content: androidx.compose.ui.graphics.Color,
) {
  ShapedIcon(icon = icon, shape = shape, containerColor = container, contentColor = content)
}

private fun clock(minuteOfDay: Int): String =
  String.format(Locale.US, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60)

private fun schemeLabel(scheme: ThemeColorScheme): String = when (scheme) {
  ThemeColorScheme.Blue -> "Aloud blue"
  ThemeColorScheme.Dynamic -> "From your wallpaper"
  ThemeColorScheme.Lagoon -> "Lagoon"
  ThemeColorScheme.Forest -> "Forest"
  ThemeColorScheme.Sunset -> "Sunset"
  ThemeColorScheme.Berry -> "Berry"
  ThemeColorScheme.Lavender -> "Lavender"
}

@Composable
private fun TimeDialog(initialMinute: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
  val state = rememberTimePickerState(
    initialHour = initialMinute / 60,
    initialMinute = initialMinute % 60,
    is24Hour = true,
  )
  AlertDialog(
    onDismissRequest = onDismiss,
    text = { TimePicker(state = state) },
    confirmButton = {
      TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text("Set") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

/** One of light, dark or follow-the-system, shown as a miniature of the app in those colours. */
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
  val borderColor =
    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = modifier
      .clip(RoundedCornerShape(16.dp))
      .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
  ) {
    Canvas(
      Modifier
        .fillMaxWidth()
        .aspectRatio(0.75f)
        .clip(shape)
        .border(if (selected) 3.dp else 1.dp, borderColor, shape),
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
        ThemeMode.FollowSystem -> "System"
        ThemeMode.Light -> "Light"
        ThemeMode.Dark -> "Dark"
      },
      style = MaterialTheme.typography.labelLarge,
      color = LocalContentColor.current.copy(alpha = if (selected) 1f else 0.7f),
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(top = 8.dp),
    )
  }
}

/** A cover, a title, a progress bar and a play button: just enough to look like the app. */
private fun DrawScope.drawMiniScreen(colors: ColorScheme) {
  drawRect(colors.surface)
  val cover = size.width * 0.5f
  val coverTop = size.height * 0.1f
  drawRoundRect(
    color = colors.primaryContainer,
    topLeft = Offset((size.width - cover) / 2, coverTop),
    size = Size(cover, cover),
    cornerRadius = CornerRadius(cover * 0.18f),
  )
  val line = size.height * 0.035f
  val titleTop = coverTop + cover + size.height * 0.05f
  drawRoundRect(
    color = colors.onSurface,
    topLeft = Offset(size.width * 0.24f, titleTop),
    size = Size(size.width * 0.52f, line),
    cornerRadius = CornerRadius(line / 2),
  )
  drawRoundRect(
    color = colors.onSurfaceVariant,
    alpha = 0.6f,
    topLeft = Offset(size.width * 0.32f, titleTop + line * 1.8f),
    size = Size(size.width * 0.36f, line),
    cornerRadius = CornerRadius(line / 2),
  )
  val barTop = titleTop + line * 4.6f
  drawRoundRect(
    color = colors.primary.copy(alpha = 0.25f),
    topLeft = Offset(size.width * 0.16f, barTop),
    size = Size(size.width * 0.68f, line * 0.8f),
    cornerRadius = CornerRadius(line / 2),
  )
  drawRoundRect(
    color = colors.primary,
    topLeft = Offset(size.width * 0.16f, barTop),
    size = Size(size.width * 0.4f, line * 0.8f),
    cornerRadius = CornerRadius(line / 2),
  )
  drawCircle(
    color = colors.primary,
    radius = size.width * 0.1f,
    center = Offset(size.width / 2, size.height * 0.87f),
  )
}

/** A colour scheme to pick, shown as the colour it grows from. */
@Composable
private fun PaletteSwatch(scheme: ThemeColorScheme, selected: Boolean, onClick: () -> Unit) {
  val swatch = if (scheme == ThemeColorScheme.Dynamic) {
    rememberThemeColorScheme(scheme, dark = false).primary
  } else {
    seedColor(scheme)
  }
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
    if (selected) {
      Icon(
        Icons.Default.Check,
        contentDescription = schemeLabel(scheme),
        tint = androidx.compose.ui.graphics.Color.White,
      )
    }
  }
}

private const val DEFAULT_ENGINE = "com.google.android.tts"
