package com.akhouri.aloud.util

import com.akhouri.aloud.AppPaths
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records what went wrong, so it can be shown on the next launch and copied out.
 * A crash is recorded as fatal; a failure the app recovered from is recorded too, in case it
 * turns out to explain something.
 */
object CrashReporter {

  private const val MAX_CHARS = 64 * 1024

  fun install() {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
      runCatching { write(error, fatal = true, where = thread.name) }
      previous?.uncaughtException(thread, error)
    }
  }

  fun record(error: Throwable, where: String? = null) {
    System.err.println("Aloud recovered from a failure${where?.let { " ($it)" }.orEmpty()}: $error")
    runCatching { write(error, fatal = false, where = where) }
  }

  fun lastReport(): String? =
    AppPaths.crashReport.takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

  fun clear() {
    runCatching { AppPaths.crashReport.delete() }
  }

  private fun write(error: Throwable, fatal: Boolean, where: String?) {
    // A recovered failure never overwrites the record of a crash.
    if (!fatal && lastReport()?.startsWith("FATAL") == true) return
    val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
    val report = buildString {
      appendLine(if (fatal) "FATAL" else "RECOVERED")
      appendLine("when: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
      appendLine("where: ${where ?: "unknown"}")
      appendLine("app: Aloud ${com.akhouri.aloud.AppPaths.version}")
      appendLine("system: ${System.getProperty("os.name")} ${System.getProperty("os.version")}, Java ${System.getProperty("java.version")}")
      appendLine()
      append(trace)
    }
    AppPaths.crashReport.writeText(report.take(MAX_CHARS))
  }
}
