package com.akhouri.aloud.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Shown once after a crash, so the trace can be copied out and sent on. An app installed from
 * a file has nothing collecting this for it.
 */
@Composable
fun CrashReportScreen(report: String, onDismiss: () -> Unit) {
  val context = LocalContext.current

  Surface(Modifier.fillMaxSize()) {
    Column(Modifier.padding(16.dp)) {
      Text("Aloud stopped unexpectedly", style = MaterialTheme.typography.headlineSmall)
      Text(
        text = "This is what went wrong. Copy it and send it on — it says exactly where the " +
          "fault was.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
      )

      Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.weight(1f).fillMaxWidth(),
      ) {
        Text(
          text = report,
          style = MaterialTheme.typography.bodySmall,
          fontFamily = FontFamily.Monospace,
          modifier = Modifier
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .padding(12.dp),
        )
      }

      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
      ) {
        Button(
          onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("Aloud crash", report))
          },
          modifier = Modifier.weight(1f),
        ) {
          Text("Copy")
        }
        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
          Text("Continue")
        }
      }
    }
  }
}
