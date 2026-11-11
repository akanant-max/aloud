package com.akhouri.aloud.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.akhouri.aloud.util.CrashReporter

/**
 * The housekeeping ExoPlayer would normally do, which a [androidx.media3.common.SimpleBasePlayer]
 * does not: hold audio focus, keep the CPU awake, and stop when the headphones come out.
 *
 * Audio focus is what tells the rest of Android that this app is playing media. Without it the
 * system has no reason to treat the process as an active player, other apps talk over the
 * narration, and an incoming call does not interrupt it.
 *
 * The wake lock matters because speech is generated as it plays. A conventional player hands a
 * decoded buffer to the audio hardware and can afford to sleep; this one has to wake up at the
 * end of every utterance to queue the next, and with the screen off a dozing CPU starves that.
 */
class PlaybackKeepAlive(context: Context) {

  private val appContext = context.applicationContext
  private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager

  private val wakeLock =
    powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Aloud:narration").apply {
      setReferenceCounted(false)
    }

  /** Narration should pause: focus was lost, or the headphones were pulled out. */
  var onInterrupted: (() -> Unit)? = null

  /** Focus came back after a temporary loss, such as the end of a call. */
  var onRegained: (() -> Unit)? = null

  private var focusRequest: AudioFocusRequest? = null
  private var pausedForTransientLoss = false
  private var noisyReceiverRegistered = false

  private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
    when (change) {
      AudioManager.AUDIOFOCUS_LOSS -> {
        pausedForTransientLoss = false
        onInterrupted?.invoke()
      }
      // Speech is unintelligible when ducked, so a duck request is treated as a pause. The
      // focus request below asks the system for exactly that behaviour.
      AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
      AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
      -> {
        pausedForTransientLoss = true
        onInterrupted?.invoke()
      }
      AudioManager.AUDIOFOCUS_GAIN -> {
        if (pausedForTransientLoss) {
          pausedForTransientLoss = false
          onRegained?.invoke()
        }
      }
    }
  }

  /** Pulling headphones out should not play the book aloud to the room. */
  private val noisyReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
        pausedForTransientLoss = false
        onInterrupted?.invoke()
      }
    }
  }

  /** Called when narration starts. Returns whether the system granted focus. */
  fun acquire(): Boolean {
    val granted = requestFocus()

    if (!wakeLock.isHeld) {
      // The timeout is a backstop against leaking the lock, not an expected limit; it is far
      // longer than any single listening session.
      runCatching { wakeLock.acquire(MAX_HOLD_MS) }
        .onFailure { CrashReporter.record(it, "acquiring the wake lock") }
    }

    registerNoisyReceiver()
    return granted
  }

  /** Called when narration stops for any reason. Safe to call when nothing is held. */
  fun release() {
    abandonFocus()
    if (wakeLock.isHeld) {
      runCatching { wakeLock.release() }
        .onFailure { CrashReporter.record(it, "releasing the wake lock") }
    }
    unregisterNoisyReceiver()
    pausedForTransientLoss = false
  }

  private fun requestFocus(): Boolean {
    val attributes = AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_MEDIA)
      .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
      .build()

    val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
      .setAudioAttributes(attributes)
      .setWillPauseWhenDucked(true)
      .setOnAudioFocusChangeListener(focusListener)
      .build()

    focusRequest = request

    val result = runCatching { audioManager.requestAudioFocus(request) }
      .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)

    if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
      Log.w(TAG, "Audio focus was not granted (result $result)")
    }
    return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
  }

  private fun abandonFocus() {
    focusRequest?.let { request ->
      runCatching { audioManager.abandonAudioFocusRequest(request) }
    }
    focusRequest = null
  }

  private fun registerNoisyReceiver() {
    if (noisyReceiverRegistered) return
    runCatching {
      // Targeting API 34+ requires the export flag to be stated explicitly.
      ContextCompat.registerReceiver(
        appContext,
        noisyReceiver,
        IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
        ContextCompat.RECEIVER_NOT_EXPORTED,
      )
      noisyReceiverRegistered = true
    }
  }

  private fun unregisterNoisyReceiver() {
    if (!noisyReceiverRegistered) return
    runCatching { appContext.unregisterReceiver(noisyReceiver) }
    noisyReceiverRegistered = false
  }

  private companion object {
    const val TAG = "PlaybackKeepAlive"
    const val MAX_HOLD_MS = 8 * 60 * 60 * 1000L
  }
}
