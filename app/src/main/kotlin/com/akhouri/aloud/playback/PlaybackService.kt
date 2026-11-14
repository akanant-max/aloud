package com.akhouri.aloud.playback

import android.app.PendingIntent
import android.content.Intent
import android.view.KeyEvent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.akhouri.aloud.MainActivity
import com.akhouri.aloud.graph
import com.akhouri.aloud.util.SessionLog
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
    SessionLog.add("service created")

    val player = SessionPlayer(
      player = graph.player,
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
      .setCallback(RecordingCallback)
      .build()
      // Registered by hand because nothing in the app connects to this service as a media
      // controller, which is what normally registers a session. Until it is registered the
      // service does not watch the player, so it never posts its notification or enters the
      // foreground when narration starts.
      .also { addSession(it) }
  }

  /** Changes nothing about how the session behaves; it only writes down what reaches it. */
  private object RecordingCallback : MediaSession.Callback {

    override fun onConnect(
      session: MediaSession,
      controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
      SessionLog.add("connect   ${controller.packageName} (version ${controller.controllerVersion})")
      return super.onConnect(session, controller)
    }

    override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
      SessionLog.add("disconnect ${controller.packageName}")
    }

    override fun onPlayerCommandRequest(
      session: MediaSession,
      controller: MediaSession.ControllerInfo,
      playerCommand: Int,
    ): Int {
      SessionLog.add("command   $playerCommand from ${controller.packageName}")
      return super.onPlayerCommandRequest(session, controller, playerCommand)
    }

    override fun onMediaButtonEvent(
      session: MediaSession,
      controllerInfo: MediaSession.ControllerInfo,
      intent: Intent,
    ): Boolean {
      @Suppress("DEPRECATION")
      val key = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
      SessionLog.add(
        "button    key ${key?.keyCode} action ${key?.action} from ${controllerInfo.packageName}",
      )
      return super.onMediaButtonEvent(session, controllerInfo, intent)
    }
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
    // Only the session goes. The player belongs to the app and outlives this service, so
    // narration carries on if the service is reclaimed or the notification is switched off.
    session?.release()
    session = null
    serviceScope.cancel()
    super.onDestroy()
  }
}
