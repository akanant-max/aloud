package com.akhouri.aloud.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records what went wrong so it can be read off the device.
 *
 * A sideloaded app has no crash console behind it, and getting a stack trace off an Android
 * phone otherwise means a cable and a desktop. Writing the trace to a file the app shows on
 * next launch makes a crash reportable by whoever hit it.
 */
object CrashReporter {

  private const val TAG = "Aloud"
  private const val FILE_NAME = "last_crash.txt"
  private const val MAX_BYTES = 64 * 1024

  private var appContext: Context? = null

  /** Installs the handler. Any previous one still runs, so the system dialog is unaffected. */
  fun install(context: Context) {
    appContext = context.applicationContext
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
      runCatching { write(error, fatal = true, threadName = thread.name) }
      previous?.uncaughtException(thread, error)
    }
  }

  /**
   * Records a failure the app recovered from. Narration runs on a long-lived coroutine scope,
   * where an uncaught exception would otherwise take the process down with it.
   */
  fun record(error: Throwable, context: String? = null) {
    Log.w(TAG, "Recovered from a failure${context?.let { ": $it" } ?: ""}", error)
    runCatching { write(error, fatal = false, threadName = context) }
  }

  fun lastReport(context: Context): String? {
    val file = File(context.filesDir, FILE_NAME)
    return if (file.exists()) runCatching { file.readText() }.getOrNull() else null
  }

  fun clear(context: Context) {
    runCatching { File(context.filesDir, FILE_NAME).delete() }
  }

  private fun write(error: Throwable, fatal: Boolean, threadName: String?) {
    val context = appContext ?: return
    val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
    val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    val report = buildString {
      appendLine(if (fatal) "FATAL" else "RECOVERED")
      appendLine("when: $stamp")
      appendLine("where: ${threadName ?: "unknown"}")
      appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
      appendLine()
      append(trace)
    }

    File(context.filesDir, FILE_NAME).writeText(report.take(MAX_BYTES))
  }
}
