package com.akhouri.aloud

import java.io.File

/** Where Aloud keeps its things on this PC. */
object AppPaths {

  /** %LOCALAPPDATA%\Aloud: the library, covers, settings and downloaded voices. */
  val dataDir: File = File(
    System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"),
    "Aloud",
  ).apply { mkdirs() }

  val database = File(dataDir, "aloud.db")
  val settings = File(dataDir, "settings.properties")
  val crashReport = File(dataDir, "last_crash.txt")
  val instancePort = File(dataDir, "instance.port")
  val instanceLock = File(dataDir, "instance.lock")

  val covers: File get() = File(dataDir, "covers").apply { mkdirs() }

  /** Voices downloaded or added by the user, as opposed to the one that ships with the app. */
  val userVoices: File get() = File(dataDir, "voices").apply { mkdirs() }

  /** Speech rendered ahead of the listener. Emptied at every start. */
  val speech: File get() = File(dataDir, "speech").apply { mkdirs() }

  /**
   * Files shipped beside the app: the speech engine, the bundled voice and the media-controls
   * helper. The installed app is told where they are; a development run can point at them
   * with ALOUD_RESOURCES.
   */
  val bundled: File? =
    (System.getProperty("compose.application.resources.dir") ?: System.getenv("ALOUD_RESOURCES"))
      ?.let(::File)

  val piperExe: File? get() = bundled?.let { File(it, "piper/piper.exe") }

  val bundledVoices: File? get() = bundled?.let { File(it, "voices") }

  val controlsExe: File? get() = bundled?.let { File(it, "controls/AloudControls.exe") }

  val version: String = System.getProperty("aloud.version") ?: "development"
}
