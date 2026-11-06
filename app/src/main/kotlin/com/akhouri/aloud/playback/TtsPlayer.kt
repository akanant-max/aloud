package com.akhouri.aloud.playback

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.akhouri.aloud.tts.NarrationState
import com.akhouri.aloud.tts.Narrator
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Presents narration to Android as an ordinary media player.
 *
 * Speech has no timeline of its own — no file, no duration, nothing to seek within — so the one
 * is synthesised from how much text each chapter holds. That is enough for the notification's
 * progress bar, for chapter skipping, and for headset buttons, and it keeps the media session
 * reporting the real state of the speech engine rather than pretending to be an audio stream.
 *
 * Positions are approximate by nature: the true position lives in [Narrator] as a paragraph and
 * character offset, and that is what gets persisted and resumed.
 */
@UnstableApi
class TtsPlayer(
  looper: Looper,
  private val narrator: Narrator,
  private val scope: CoroutineScope,
) : SimpleBasePlayer(looper) {

  init {
    scope.launch {
      narrator.state.collectLatest { invalidateState() }
    }
  }

  override fun getState(): State {
    val narration = narrator.state.value

    val playlist = narration.chapters.mapIndexed { index, chapter ->
      val title = chapter.title ?: "Chapter ${index + 1}"
      MediaItemData.Builder(chapter.id)
        .setMediaItem(
          MediaItem.Builder()
            .setMediaId("${narration.bookId}:$index")
            .setMediaMetadata(
              MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(narration.author ?: narration.bookTitle)
                .setAlbumTitle(narration.bookTitle)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build(),
            )
            .build(),
        )
        .setDurationUs(durationUsOf(chapter.paragraphCount, narration.speechRate))
        .build()
    }

    val builder = State.Builder()
      .setAvailableCommands(COMMANDS)
      .setPlaybackState(
        when {
          !narration.hasBook -> Player.STATE_IDLE
          narration.isPreparing -> Player.STATE_BUFFERING
          else -> Player.STATE_READY
        },
      )
      .setPlayWhenReady(narration.isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
      .setPlaybackParameters(PlaybackParameters(narration.speechRate, narration.pitch))

    if (playlist.isNotEmpty()) {
      builder
        .setPlaylist(playlist)
        .setCurrentMediaItemIndex(narration.chapterIndex.coerceIn(0, playlist.lastIndex))
        .setContentPositionMs { positionMsIn(narration) }
    }

    return builder.build()
  }

  override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

  override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
    scope.launch { if (playWhenReady) narrator.play() else narrator.pause() }
    return Futures.immediateVoidFuture()
  }

  override fun handleStop(): ListenableFuture<*> {
    scope.launch { narrator.stop() }
    return Futures.immediateVoidFuture()
  }

  override fun handleRelease(): ListenableFuture<*> {
    scope.launch { narrator.stop() }
    return Futures.immediateVoidFuture()
  }

  override fun handleSetPlaybackParameters(
    playbackParameters: PlaybackParameters,
  ): ListenableFuture<*> {
    scope.launch {
      narrator.setSpeechRate(playbackParameters.speed)
      narrator.setPitch(playbackParameters.pitch)
    }
    return Futures.immediateVoidFuture()
  }

  override fun handleSeek(
    mediaItemIndex: Int,
    positionMs: Long,
    seekCommand: Int,
  ): ListenableFuture<*> {
    scope.launch {
      val narration = narrator.state.value
      when (seekCommand) {
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> narrator.nextChapter()
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> narrator.previousChapter()
        else -> {
          val chapter = narration.chapters.getOrNull(mediaItemIndex)
          if (chapter == null) {
            narrator.seekTo(0)
          } else {
            // Turn the requested time back into a paragraph within that chapter.
            val intoChapter = if (positionMs <= 0) {
              0
            } else {
              (positionMs / paragraphMs(narration.speechRate)).toInt()
            }
            narrator.seekTo(
              chapter.startParagraph +
                intoChapter.coerceIn(0, (chapter.paragraphCount - 1).coerceAtLeast(0)),
            )
          }
        }
      }
    }
    return Futures.immediateVoidFuture()
  }

  // ---- synthetic timeline -----------------------------------------------------------------

  private fun positionMsIn(narration: NarrationState): Long {
    val chapter = narration.chapters.getOrNull(narration.chapterIndex) ?: return 0L
    val intoChapter = (narration.paragraphIndex - chapter.startParagraph).coerceAtLeast(0)
    return (intoChapter * paragraphMs(narration.speechRate))
  }

  private fun durationUsOf(paragraphCount: Int, rate: Float): Long =
    paragraphCount.coerceAtLeast(1) * paragraphMs(rate) * 1_000L

  /**
   * Roughly how long one paragraph takes to speak. Derived from a typical paragraph length and
   * a typical speaking rate, then scaled by the user's speed.
   */
  private fun paragraphMs(rate: Float): Long {
    val safeRate = rate.coerceAtLeast(0.1f)
    return (AVERAGE_PARAGRAPH_CHARS / CHARS_PER_SECOND / safeRate * 1000f).toLong()
  }

  private companion object {
    const val AVERAGE_PARAGRAPH_CHARS = 420f

    /** About 180 words a minute, which is where most engines sit at normal speed. */
    const val CHARS_PER_SECOND = 15f

    val COMMANDS: Player.Commands = Player.Commands.Builder()
      .addAll(
        Player.COMMAND_PLAY_PAUSE,
        Player.COMMAND_PREPARE,
        Player.COMMAND_STOP,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_MEDIA_ITEM,
        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        Player.COMMAND_SEEK_BACK,
        Player.COMMAND_SEEK_FORWARD,
        Player.COMMAND_SET_SPEED_AND_PITCH,
        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
        Player.COMMAND_GET_TIMELINE,
        Player.COMMAND_GET_METADATA,
        Player.COMMAND_SET_MEDIA_ITEM,
      )
      .build()
  }
}
