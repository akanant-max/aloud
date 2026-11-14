package com.akhouri.aloud.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A short running record of what reaches the media session: who connected, and which commands
 * and button presses arrived.
 *
 * A watch or car talks to the app through a companion app and the system, and when a button
 * there does nothing, the only way to tell whether the press never arrived or arrived and was
 * mishandled is to look. This keeps the last few hundred events in memory, to be read from the
 * settings screen.
 */
object SessionLog {

  private const val MAX_LINES = 300
  private val lines = ArrayDeque<String>()
  private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

  @Synchronized
  fun add(event: String) {
    lines.addLast("${clock.format(Date())}  $event")
    while (lines.size > MAX_LINES) lines.removeFirst()
  }

  @Synchronized
  fun text(): String =
    if (lines.isEmpty()) "Nothing recorded yet." else lines.joinToString("\n")

  @Synchronized
  fun clear() {
    lines.clear()
  }
}
