package com.akhouri.aloud

import android.Manifest
import android.content.Intent
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
import com.akhouri.aloud.playback.PlaybackService
import com.akhouri.aloud.ui.AloudNavHost
import com.akhouri.aloud.ui.CrashReportScreen
import com.akhouri.aloud.ui.theme.AloudTheme
import com.akhouri.aloud.util.CrashReporter

class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    // Starting the service here means the media session exists before anything is played, so
    // the first press of a Bluetooth button has something to reach.
    requestNotificationPermission()
    startPlaybackService()

    val crashReport = CrashReporter.lastReport(this)

    setContent {
      val settings by graph.settings.settings.collectAsState(initial = Settings())
      var showCrash by remember { mutableStateOf(crashReport != null) }

      AloudTheme(themeMode = settings.themeMode, pureBlack = settings.pureBlack) {
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

  /**
   * Brings the media session up so a headset button has something to reach before anything has
   * been played.
   *
   * Deliberately a plain start, not a foreground one. Starting it in the foreground obliges the
   * service to call startForeground within five seconds, and Media3 only promotes the service
   * once playback is actually running — missing that window is fatal. A plain start is allowed
   * because the activity is in the foreground, and Media3 promotes the service itself when
   * narration begins.
   */
  private fun startPlaybackService() {
    runCatching { startService(Intent(this, PlaybackService::class.java)) }
      .onFailure { CrashReporter.record(it, "starting the playback service") }
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
