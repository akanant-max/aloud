package com.akhouri.aloud.playback

import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.akhouri.aloud.tts.Narrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The player as the media session sees it.
 *
 * Underneath is a real player working through a playlist of short rendered files, which is the
 * wrong shape to show the outside world: "next" would mean the next few sentences and the
 * progress bar would span half a minute. This wrapper presents the book instead. Next and
 * previous move by chapter, play and pause go through the narrator so they work even before
 * anything has been rendered, and the length of the current file is kept to itself.
 */
@UnstableApi
class SessionPlayer(
  player: Player,
  private val narrator: Narrator,
  private val scope: CoroutineScope,
) : ForwardingPlayer(player) {

  override fun getAvailableCommands(): Player.Commands =
    super.getAvailableCommands().buildUpon()
      .add(Player.COMMAND_PLAY_PAUSE)
      .add(Player.COMMAND_SEEK_TO_NEXT)
      .add(Player.COMMAND_SEEK_TO_PREVIOUS)
      .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
      .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
      // Scrubbing within a file of a few sentences means nothing to the listener.
      .remove(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
      .remove(Player.COMMAND_SEEK_BACK)
      .remove(Player.COMMAND_SEEK_FORWARD)
      .build()

  override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

  override fun play() {
    scope.launch { narrator.play() }
  }

  override fun pause() {
    scope.launch { narrator.pause() }
  }

  override fun setPlayWhenReady(playWhenReady: Boolean) {
    scope.launch { if (playWhenReady) narrator.play() else narrator.pause() }
  }

  override fun stop() {
    scope.launch { narrator.pause() }
  }

  override fun hasNextMediaItem(): Boolean = true

  override fun hasPreviousMediaItem(): Boolean = true

  override fun seekToNext() {
    scope.launch { narrator.nextChapter() }
  }

  override fun seekToNextMediaItem() {
    scope.launch { narrator.nextChapter() }
  }

  override fun seekToPrevious() {
    scope.launch { narrator.previousChapter() }
  }

  override fun seekToPreviousMediaItem() {
    scope.launch { narrator.previousChapter() }
  }

  override fun isCurrentMediaItemSeekable(): Boolean = false

  override fun getDuration(): Long = C.TIME_UNSET

  override fun getContentDuration(): Long = C.TIME_UNSET
}
