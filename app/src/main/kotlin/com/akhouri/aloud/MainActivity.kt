package com.akhouri.aloud

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.akhouri.aloud.data.Settings
import com.akhouri.aloud.ui.AloudNavHost
import com.akhouri.aloud.ui.CrashReportScreen
import com.akhouri.aloud.ui.theme.AloudTheme
import com.akhouri.aloud.util.CrashReporter

class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    // The playback service itself is started by the app graph, which knows whether the
    // notification is switched on.
    requestNotificationPermission()

    val crashReport = CrashReporter.lastReport(this)

    setContent {
      val settings by graph.settings.settings.collectAsState(initial = Settings())
      var showCrash by remember { mutableStateOf(crashReport != null) }

      AloudTheme(
        themeMode = settings.themeMode,
        colorScheme = settings.themeColorScheme,
        pureBlack = settings.pureBlack,
      ) {
        if (showCrash && crashReport != null) {
          CrashReportScreen(
            report = crashReport,
            onDismiss = {
              CrashReporter.clear(this@MainActivity)
              showCrash = false
            },
          )
        } else {
          AloudNavHost()
        }
      }
    }
  }

  /** Without this the media notification is silently suppressed on Android 13 and up. */
  private fun requestNotificationPermission() {
    if (Build.VERSION.SDK_INT < 33) return
    val granted = ContextCompat.checkSelfPermission(
      this,
      Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
    if (!granted) {
      runCatching {
        ActivityCompat.requestPermissions(
          this,
          arrayOf(Manifest.permission.POST_NOTIFICATIONS),
          1,
        )
      }
    }
  }
}
