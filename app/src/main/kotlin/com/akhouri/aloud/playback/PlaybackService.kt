package com.akhouri.aloud.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.akhouri.aloud.MainActivity
import com.akhouri.aloud.graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Keeps narration running when the app is not on screen.
 *
 * A foreground media service is what Android requires for audio that survives the screen going
 * off, and the media session attached to it is what gives Bluetooth buttons, the lock screen and
 * the notification something to talk to. There is exactly one of each.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

  private var session: MediaSession? = null
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  override fun onCreate() {
    super.onCreate()

    val player = TtsPlayer(
      looper = mainLooper,
      narrator = graph.narrator,
      scope = serviceScope,
    )

    val openApp = PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java)
        .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    session = MediaSession.Builder(this, player)
      .setSessionActivity(openApp)
      .build()
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

  /**
   * Swiping the app away should not cut off narration mid-sentence when it is still playing —
   * that is the whole point of background playback. It only tears down once nothing is playing.
   */
  override fun onTaskRemoved(rootIntent: Intent?) {
    val player = session?.player
    if (player == null || !player.playWhenReady) {
      stopSelf()
    }
  }

  override fun onDestroy() {
    session?.run {
      player.release()
      release()
    }
    session = null
    serviceScope.cancel()
    super.onDestroy()
  }
}
