package com.akhouri.aloud.tts

import java.io.File

/**
 * Makes a Piper voice's settings file readable by the Piper build Aloud ships.
 *
 * Newer settings files list multi-character phonemes such as "aɪ" in phoneme_id_map, and that
 * build refuses the whole voice because of them. It only ever looks up single characters, so
 * those entries are dropped; the voice sounds exactly the same.
 */
object VoiceConfigFix {

  private val MAP = Regex("(\"phoneme_id_map\"\\s*:\\s*\\{)([^{}]*)(\\})")
  private val ENTRY = Regex("\"((?:\\\\.|[^\"\\\\])*)\"\\s*:\\s*\\[[^\\]]*\\]\\s*,?")

  /** Rewrites [config] if it needs it. Returns true if anything changed. */
  fun apply(config: File): Boolean {
    if (!config.isFile) return false
    val text = config.readText(Charsets.UTF_8)
    val fixed = fix(text) ?: return false
    val temp = File(config.path + ".tmp")
    temp.writeText(fixed, Charsets.UTF_8)
    if (!temp.renameTo(config)) {
      config.writeText(fixed, Charsets.UTF_8)
      temp.delete()
    }
    return true
  }

  /** The fixed text, or null when nothing needs changing. */
  fun fix(text: String): String? {
    val map = MAP.find(text) ?: return null
    val body = map.groupValues[2]
    val kept = mutableListOf<String>()
    var dropped = 0
    for (entry in ENTRY.findAll(body)) {
      val key = unescape(entry.groupValues[1])
      if (key.codePointCount(0, key.length) == 1) {
        kept += entry.value.trim().removeSuffix(",").trim()
      } else {
        dropped++
      }
    }
    if (dropped == 0) return null
    val rebuilt = kept.joinToString(",\n    ", prefix = "\n    ", postfix = "\n  ")
    return text.replaceRange(map.groups[2]!!.range, rebuilt)
  }

  private fun unescape(s: String): String {
    if ('\\' !in s) return s
    val out = StringBuilder()
    var i = 0
    while (i < s.length) {
      val c = s[i]
      if (c == '\\' && i + 1 < s.length) {
        val n = s[i + 1]
        if (n == 'u' && i + 5 < s.length) {
          out.append(s.substring(i + 2, i + 6).toInt(16).toChar())
          i += 6
          continue
        }
        out.append(
          when (n) {
            'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; 'b' -> '\b'; 'f' -> '\u000c'
            else -> n
          },
        )
        i += 2
        continue
      }
      out.append(c)
      i++
    }
    return out.toString()
  }
}
