package com.akhouri.aloud.playback

import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.akhouri.aloud.tts.Narrator
import com.akhouri.aloud.util.SessionLog
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
    SessionLog.add("player    play()")
    scope.launch { narrator.play() }
  }

  override fun pause() {
    SessionLog.add("player    pause()")
    scope.launch { narrator.pause() }
  }

  override fun setPlayWhenReady(playWhenReady: Boolean) {
    SessionLog.add("player    setPlayWhenReady($playWhenReady)")
    scope.launch { if (playWhenReady) narrator.play() else narrator.pause() }
  }

  override fun stop() {
    SessionLog.add("player    stop()")
    scope.launch { narrator.pause() }
  }

  override fun hasNextMediaItem(): Boolean = true

  override fun hasPreviousMediaItem(): Boolean = true

  override fun seekToNext() {
    SessionLog.add("player    seekToNext()")
    scope.launch { narrator.nextChapter() }
  }

  override fun seekToNextMediaItem() {
    SessionLog.add("player    seekToNextMediaItem()")
    scope.launch { narrator.nextChapter() }
  }

  override fun seekToPrevious() {
    SessionLog.add("player    seekToPrevious()")
    scope.launch { narrator.previousChapter() }
  }

  override fun seekToPreviousMediaItem() {
    SessionLog.add("player    seekToPreviousMediaItem()")
    scope.launch { narrator.previousChapter() }
  }

  // Not acted on, but recorded: a control that sends one of these is asking for something
  // this wrapper does not yet translate.
  override fun seekTo(positionMs: Long) {
    SessionLog.add("player    seekTo($positionMs) ignored")
  }

  override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
    SessionLog.add("player    seekTo(item $mediaItemIndex, $positionMs) ignored")
  }

  override fun seekBack() {
    SessionLog.add("player    seekBack()")
    scope.launch { narrator.skipParagraphs(-1) }
  }

  override fun seekForward() {
    SessionLog.add("player    seekForward()")
    scope.launch { narrator.skipParagraphs(1) }
  }

  override fun prepare() {
    SessionLog.add("player    prepare()")
    super.prepare()
  }

  override fun isCurrentMediaItemSeekable(): Boolean = false

  override fun getDuration(): Long = C.TIME_UNSET

  override fun getContentDuration(): Long = C.TIME_UNSET
}
