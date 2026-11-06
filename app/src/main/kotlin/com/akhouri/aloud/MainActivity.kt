package com.akhouri.aloud

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.core.content.ContextCompat
import com.akhouri.aloud.data.Settings
import com.akhouri.aloud.playback.PlaybackService
import com.akhouri.aloud.ui.AloudNavHost
import com.akhouri.aloud.ui.theme.AloudTheme

class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    // Starting the service here means the media session exists before anything is played, so
    // the first press of a Bluetooth button has something to reach.
    startPlaybackService()

    setContent {
      val settings by graph.settings.settings.collectAsState(initial = Settings())
      AloudTheme(themeMode = settings.themeMode, pureBlack = settings.pureBlack) {
        AloudNavHost()
      }
    }
  }

  private fun startPlaybackService() {
    val intent = Intent(this, PlaybackService::class.java)
    runCatching {
      if (Build.VERSION.SDK_INT >= 26) {
        ContextCompat.startForegroundService(this, intent)
      } else {
        startService(intent)
      }
    }
  }
}
