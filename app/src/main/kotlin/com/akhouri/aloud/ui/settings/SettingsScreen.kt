package com.akhouri.aloud.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.akhouri.aloud.data.Settings
import com.akhouri.aloud.data.ThemeMode
import com.akhouri.aloud.graph
import com.akhouri.aloud.tts.EngineInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val appGraph = context.graph
  val scope = rememberCoroutineScope()

  var settings by remember { mutableStateOf(Settings()) }
  var engines by remember { mutableStateOf<List<EngineInfo>>(emptyList()) }
  var voices by remember { mutableStateOf<List<String>>(emptyList()) }
  var voicesOpen by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) {
    settings = appGraph.settings.settings.first()
    engines = appGraph.ttsEngine.availableEngines()
  }

  // Voices can only be listed once an engine is actually running.
  LaunchedEffect(settings.enginePackage) {
    appGraph.ttsEngine.ensureReady(settings.enginePackage)
    voices = appGraph.ttsEngine.voices().map { it.name }.take(60)
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Settings") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
      )
    },
  ) { padding ->
    Column(
      Modifier
        .padding(padding)
        .verticalScroll(rememberScrollState())
        .padding(bottom = 32.dp),
    ) {

      Header("Voice")

      if (engines.isEmpty()) {
        Body(
          "No speech engine was found. Install Google Text-to-Speech from the Play Store, " +
            "then come back here.",
        )
      }

      engines.forEach { engine ->
        ChoiceRow(
          label = engine.label,
          secondary = engine.packageName,
          selected = settings.enginePackage == engine.packageName ||
            (settings.enginePackage == null && engine.packageName == DEFAULT_ENGINE),
          onClick = {
            scope.launch {
              appGraph.settings.setEngine(engine.packageName)
              appGraph.settings.setVoice(null)
              settings = appGraph.settings.settings.first()
              appGraph.narrator.useVoice(engine.packageName, null)
            }
          },
        )
      }

      if (voices.isNotEmpty()) {
        // Folded by default: an engine can offer dozens of voices, and the list would
        // otherwise push every other setting off the screen.
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier
            .fillMaxWidth()
            .clickable { voicesOpen = !voicesOpen }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
          Column(Modifier.weight(1f)) {
            Text("Voice", style = MaterialTheme.typography.bodyLarge)
            Text(
              text = settings.voiceName ?: "The engine's default",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          Icon(
            imageVector = if (voicesOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (voicesOpen) "Hide the voices" else "Show the voices",
          )
        }
        if (voicesOpen) {
          Body("Only offline voices are listed, so narration keeps working without a connection.")
          voices.forEach { voice ->
            ChoiceRow(
              label = voice,
              selected = settings.voiceName == voice,
              onClick = {
                scope.launch {
                  appGraph.settings.setVoice(voice)
                  settings = appGraph.settings.settings.first()
                  appGraph.narrator.useVoice(settings.enginePackage, voice)
                }
              },
            )
          }
        }
      }

      HorizontalDivider(Modifier.padding(vertical = 8.dp))
      Header("Playback")

      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
      ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
          Text("Playback notification", style = MaterialTheme.typography.bodyLarge)
          Text(
            text = "Off removes the notification along with the lock-screen and headset " +
              "controls, and Android may then stop narration when the app is in the background.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(
          checked = settings.mediaNotification,
          onCheckedChange = { enabled ->
            scope.launch {
              appGraph.settings.setMediaNotification(enabled)
              settings = appGraph.settings.settings.first()
            }
          },
        )
      }

      HorizontalDivider(Modifier.padding(vertical = 8.dp))
      Header("Appearance")

      ThemeMode.entries.forEach { mode ->
        ChoiceRow(
          label = when (mode) {
            ThemeMode.FollowSystem -> "Follow the system"
            ThemeMode.Light -> "Light"
            ThemeMode.Dark -> "Dark"
          },
          selected = settings.themeMode == mode,
          onClick = {
            scope.launch {
              appGraph.settings.setThemeMode(mode)
              settings = appGraph.settings.settings.first()
            }
          },
        )
      }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
      ) {
        Column(Modifier.weight(1f)) {
          Text("Pure black in dark mode", style = MaterialTheme.typography.bodyLarge)
          Text(
            text = "Saves battery on an OLED screen",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(
          checked = settings.pureBlack,
          onCheckedChange = { enabled ->
            scope.launch {
              appGraph.settings.setPureBlack(enabled)
              settings = appGraph.settings.settings.first()
            }
          },
        )
      }

      HorizontalDivider(Modifier.padding(vertical = 8.dp))
      Header("About")
      Body(
        "Aloud reads EPUB, PDF with a text layer, Kindle books without DRM (MOBI, AZW3), FB2, " +
          "Word, HTML and plain text. Every book is converted to plain text on import, on " +
          "this device — no account, no server, no connection needed once a voice is installed.",
      )
    }
  }
}

@Composable
private fun Header(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp),
  )
}

@Composable
private fun Body(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
  )
}

@Composable
private fun ChoiceRow(
  label: String,
  selected: Boolean,
  onClick: () -> Unit,
  secondary: String? = null,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 20.dp, vertical = 10.dp),
  ) {
    RadioButton(selected = selected, onClick = onClick)
    Column(Modifier.weight(1f).padding(start = 8.dp)) {
      Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
      secondary?.let {
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
}

private const val DEFAULT_ENGINE = "com.google.android.tts"
