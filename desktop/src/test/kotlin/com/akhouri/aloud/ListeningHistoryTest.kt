package com.akhouri.aloud

import com.akhouri.aloud.data.HistoryEntry
import com.akhouri.aloud.data.ListeningHistory
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test

class ListeningHistoryTest {

  private fun entry(time: Long, paragraph: Int, reason: String, awake: Boolean = true, snippet: String = "Text") =
    HistoryEntry(time, paragraph, 0, paragraph / 10, "Chapter\twith tab", reason, snippet, awake)

  @Test
  fun keepsNewestFirstSurvivesReloadAndFoldsRepeats() {
    val dir = Files.createTempDirectory("history").toFile()
    val file = dir.resolve("h.tsv")
    val scope = CoroutineScope(Dispatchers.IO)
    val history = ListeningHistory(file, scope)
    history.record("b", entry(1_000_000, 5, ListeningHistory.PRESSED_PLAY, snippet = "Line one\nline two \\ slash"))
    // Same spot moments later: folded into the first.
    history.record("b", entry(1_010_000, 6, ListeningHistory.PAUSED))
    history.record("b", entry(5_000_000, 80, ListeningHistory.SLEEP_TIMER, awake = false))

    val list = history.forBook("b")
    assertThat(list.map { it.paragraphIndex }).containsExactly(80, 5).inOrder()
    assertThat(history.latestAwake("b")?.paragraphIndex).isEqualTo(5)

    repeat(50) { if (!file.isFile) Thread.sleep(40) }
    Thread.sleep(200)
    val reloaded = ListeningHistory(file, scope).forBook("b")
    assertThat(reloaded).isEqualTo(list)
    assertThat(reloaded.last().snippet).isEqualTo("Line one\nline two \\ slash")
    assertThat(reloaded.last().chapterTitle).isEqualTo("Chapter\twith tab")
    dir.deleteRecursively()
  }
}
