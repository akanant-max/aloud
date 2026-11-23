package com.akhouri.aloud

import com.akhouri.aloud.tts.VoiceConfigFix
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VoiceConfigFixTest {

  private val newer = """
    {
      "audio": { "sample_rate": 22050 },
      "phoneme_id_map": {
        "_": [0],
        "a": [14],
        "ɑ": [51],
        "aɪ": [161],
        "\"": [150],
        "aɪ": [162],
        "ʊ": [100]
      },
      "num_symbols": 256
    }
  """.trimIndent()

  @Test
  fun dropsMultiCharacterPhonemesAndKeepsTheRest() {
    val fixed = VoiceConfigFix.fix(newer)!!
    assertThat(fixed).doesNotContain("aɪ")
    assertThat(fixed).doesNotContain("[161]")
    assertThat(fixed).doesNotContain("[162]")
    listOf("\"_\": [0]", "\"a\": [14]", "[51]", "\"\\\"\": [150]", "\"ʊ\": [100]").forEach {
      assertThat(fixed).contains(it)
    }
    assertThat(fixed).contains("\"num_symbols\": 256")
    assertThat(fixed).contains("\"audio\": { \"sample_rate\": 22050 }")
    // No trailing comma before the closing brace.
    assertThat(fixed.replace(Regex("\\s"), "")).doesNotContain(",}")
  }

  @Test
  fun leavesAnOlderFileAlone() {
    assertThat(VoiceConfigFix.fix("""{"phoneme_id_map": {"a": [1], "b": [2]}}""")).isNull()
  }
}
